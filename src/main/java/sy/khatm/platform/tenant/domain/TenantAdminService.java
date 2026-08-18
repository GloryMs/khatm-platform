package sy.khatm.platform.tenant.domain;

import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.key.api.TenantKeyProvisioner;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantView;
import sy.khatm.platform.tenant.persistence.TenantRepository;

/**
 * Default {@link TenantAdmin} implementation — the tenant admin/onboarding plane (spec FS-2.1 D6,
 * {@code /api/v1/admin/tenants}), mirroring {@code consumer.domain.ConsumingPartyAdminService}'s
 * shape deliberately.
 *
 * <p><b>Cross-tenant, not tenant-scoped:</b> unlike every other admin plane in this codebase
 * (consuming parties, schemas — all scoped to {@code TenantContext.current()}), this one manages
 * tenants themselves platform-wide: {@link #list()} returns every tenant, and every operation takes
 * an explicit target tenant id/slug rather than resolving one from ambient context. The calling
 * admin's own tenant is irrelevant here — it is, by construction, never the tenant being onboarded.
 *
 * <p>This class is module-private. External code must depend on {@link TenantAdmin}, not this
 * class.
 */
@Service
class TenantAdminService implements TenantAdmin {

  /** Same slug regex as the consuming-party admin plane's {@code code} (KH-1.4.4 D2). */
  private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-_]{1,62}$");

  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_SUSPENDED = "SUSPENDED";
  private static final String DEFAULT_DEPLOY_MODE = "SAAS";
  private static final String DEFAULT_KEY_PROVIDER = "SOFT";

  /** Max tenant hierarchy depth (spec FS-2.5 §7, resolved 2026-08-18: three levels). */
  private static final int MAX_DEPTH = 3;

  private final TenantRepository tenants;
  private final TenantKeyProvisioner keyProvisioner;
  private final StatusListAllocator statusLists;
  private final AuditService audit;

  TenantAdminService(
      TenantRepository tenants,
      TenantKeyProvisioner keyProvisioner,
      StatusListAllocator statusLists,
      AuditService audit) {
    this.tenants = tenants;
    this.keyProvisioner = keyProvisioner;
    this.statusLists = statusLists;
    this.audit = audit;
  }

  @Override
  public TenantView create(String slug, LocalizedText nameI18n, String type, String deployMode) {
    if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
      throw new ValidationException(ErrorCode.KH_TNT_0400, "tenant.invalid-slug");
    }

    Tenant tenant = tenants.findBySlug(slug).orElse(null);
    boolean freshRow = tenant == null;
    if (freshRow) {
      tenant = insertNewRow(slug, nameI18n, type, deployMode);
      audit.record(AuditAction.TENANT_CREATED, "tenant", slug, null);
    }

    // KH-2.1 Part B (spec D2/D4): issuer_key/status_list are RLS-protected, and the calling admin's
    // own ambient tenant (whatever TenantContextFilter resolved from THEIR principal) is never the
    // tenant being onboarded — RLS's tenant_isolation policy would hide/reject reads and inserts
    // under the admin's own tenant_id. This scope also covers the hasActiveKey conflict check below
    // (not just provisioning): checking an EXISTING tenant's key under the wrong ambient context
    // would always see zero rows and silently fall through to the resume path instead of detecting
    // a genuine duplicate. Deliberately NOT @Transactional on this method (each step below is
    // already independently idempotent per the resumable-onboarding design below, and each needs
    // its own fresh physical transaction anyway so shared.TenantContextTransactionExecutionListener
    // re-fires and picks up the target tenant set here, not the admin caller's own).
    //
    // KH-2.2a note (spec FS-2.2 D4): NOT wired through shared.OnBehalfOfExecutor — unlike {@code
    // rbac.web.AuthController#createApiKey} (one endpoint serving both a self-service and a
    // cross-tenant caller, so the platform:admin check can only live in code, not a URL-pattern
    // rule), this whole /api/v1/admin/tenants/** path is already platform:admin-exclusive at the
    // HTTP boundary (rbac.security.SecurityConfig's ADMIN_TENANTS_PATH rule) with no other caller,
    // so an additional in-service check here would be pure redundancy — and would break every
    // service-level test in this suite (TenantAdminServiceTest calls create() directly,
    // deliberately
    // with no SecurityContext at all, per this codebase's established "service tests bypass HTTP
    // auth, *GateTest classes cover it" convention).
    TenantContext.set(tenant.getId(), tenant.getSlug());
    try {
      if (!freshRow && keyProvisioner.hasActiveKey(tenant.getId())) {
        // Already fully onboarded — a second explicit create is a genuine conflict, never a silent
        // overwrite (spec D9, same stance as consumer.domain.ConsumingPartyAdminService#create).
        throw new ConflictException(ErrorCode.KH_TNT_0409, "tenant.duplicate-slug");
      }
      // Else: a tenant row exists but has no ACTIVE key yet — a prior onboarding attempt died
      // between steps (spec V3). Resume rather than reject; every step below is independently
      // idempotent.
      keyProvisioner.provisionFirstKey(tenant.getId(), tenant.getSlug());
      statusLists.ensureList(tenant.getId(), defaultListCode(tenant.getSlug()));
    } finally {
      TenantContext.clear();
    }

    return toView(tenant);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantView> list() {
    return tenants.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public TenantView get(UUID id) {
    return toView(require(id));
  }

  @Override
  @Transactional
  public TenantView suspend(UUID id) {
    return flipStatus(id, STATUS_SUSPENDED, AuditAction.TENANT_SUSPENDED, true);
  }

  @Override
  @Transactional
  public TenantView activate(UUID id) {
    return flipStatus(id, STATUS_ACTIVE, AuditAction.TENANT_ACTIVATED, false);
  }

  @Override
  @Transactional
  public TenantView setParent(UUID id, String parentSlug) {
    Tenant child = require(id);

    if (parentSlug == null || parentSlug.isBlank()) {
      if (child.getParentTenantId() != null) {
        String previousParentSlug =
            tenants.findById(child.getParentTenantId()).map(Tenant::getSlug).orElse(null);
        child.setParentTenantId(null);
        tenants.save(child);
        audit.record(
            AuditAction.TENANT_PARENT_UNLINKED,
            "tenant",
            child.getSlug(),
            Map.of("previousParentSlug", previousParentSlug == null ? "" : previousParentSlug));
      }
      return toView(child);
    }

    // Checked against the raw request string first — reachable even though a fresh lookup of the
    // child's own slug would just resolve back to `child` itself, spec FS-2.5 §2/§7.
    if (parentSlug.equals(child.getSlug())) {
      throw new ValidationException(ErrorCode.KH_TNT_0422, "tenant.parent-self");
    }
    Tenant parent =
        tenants
            .findBySlug(parentSlug)
            .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));
    if (parent.getId().equals(child.getId())) {
      throw new ValidationException(ErrorCode.KH_TNT_0422, "tenant.parent-self");
    }

    int parentDepth = ancestorDepthRejectingCycle(parent, child.getId());
    if (!STATUS_ACTIVE.equals(parent.getStatus())) {
      throw new ValidationException(ErrorCode.KH_TNT_3422, "tenant.parent-not-active");
    }
    int subtreeExtension = maxDescendantExtension(child.getId(), MAX_DEPTH);
    if (parentDepth + 1 + subtreeExtension > MAX_DEPTH) {
      throw new ValidationException(ErrorCode.KH_TNT_2422, "tenant.parent-depth-exceeded");
    }

    child.setParentTenantId(parent.getId());
    tenants.save(child);
    audit.record(
        AuditAction.TENANT_PARENT_LINKED,
        "tenant",
        child.getSlug(),
        Map.of("parentSlug", parent.getSlug()));
    return toView(child);
  }

  /**
   * Depth of {@code parent} (root = 1), walking its own ancestor chain — and rejecting with {@code
   * KH-TNT-1422} the moment {@code childId} is found among those ancestors, since that means {@code
   * parent} is a descendant of {@code childId} and linking would create a cycle.
   */
  private int ancestorDepthRejectingCycle(Tenant parent, UUID childId) {
    int depth = 1;
    Set<UUID> seen = new HashSet<>();
    Tenant cursor = parent;
    seen.add(cursor.getId());
    while (cursor.getParentTenantId() != null) {
      if (cursor.getParentTenantId().equals(childId)) {
        throw new ValidationException(ErrorCode.KH_TNT_1422, "tenant.parent-cycle");
      }
      cursor =
          tenants
              .findById(cursor.getParentTenantId())
              .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));
      if (!seen.add(cursor.getId())) {
        // Pre-existing corrupt cycle upstream of this call (should be unreachable given this same
        // guard plus the DB trigger) — stop rather than loop forever; not this call's cycle to
        // report.
        break;
      }
      depth++;
    }
    return depth;
  }

  /**
   * How many additional levels {@code tenantId}'s own deepest existing descendant chain would add
   * below it (0 if it has no children) — needed so re-parenting a tenant that already has children
   * of its own cannot silently push a grandchild past the max depth.
   */
  private int maxDescendantExtension(UUID tenantId, int budget) {
    if (budget <= 0) {
      return 0;
    }
    List<Tenant> children = tenants.findAllByParentTenantId(tenantId);
    int max = 0;
    for (Tenant childOfChild : children) {
      max = Math.max(max, 1 + maxDescendantExtension(childOfChild.getId(), budget - 1));
    }
    return max;
  }

  private Tenant insertNewRow(String slug, LocalizedText nameI18n, String type, String deployMode) {
    Tenant tenant = new Tenant();
    tenant.setId(Uuidv7.generate());
    tenant.setSlug(slug);
    tenant.setNameI18n(nameI18n);
    tenant.setType(type);
    tenant.setDeployMode(
        deployMode == null || deployMode.isBlank() ? DEFAULT_DEPLOY_MODE : deployMode);
    // Spec FS-2.3 D5/D6, veto V3: every tenant is born on SOFT — migrating onto a KMS-backed
    // provider (Vault Transit today) is always an explicit later rotation, never an onboarding
    // choice (see Tenant#keyProvider's own Javadoc).
    tenant.setKeyProvider(DEFAULT_KEY_PROVIDER);
    tenant.setStatus(STATUS_ACTIVE);
    Instant now = Instant.now();
    tenant.setCreatedAt(now);
    tenant.setUpdatedAt(now);
    try {
      tenants.saveAndFlush(tenant);
      return tenant;
    } catch (DataIntegrityViolationException raced) {
      // Concurrent create of the same brand-new slug: the UNIQUE constraint caught it. Report it
      // the same way as the pre-check above — a duplicate, never a second row (mirrors
      // ConsumingPartyAdminService#create's identical race-handling shape).
      throw new ConflictException(ErrorCode.KH_TNT_0409, "tenant.duplicate-slug");
    }
  }

  private TenantView flipStatus(
      UUID id, String status, AuditAction action, boolean guardActiveChildren) {
    Tenant tenant = require(id);
    if (!status.equals(tenant.getStatus())) {
      // No cascade, ever (spec FS-2.5 §2): only guarded on the transition itself, so a second
      // idempotent suspend call on an already-SUSPENDED tenant never re-evaluates this and stays a
      // true no-op.
      if (guardActiveChildren && tenants.existsByParentTenantIdAndStatus(id, STATUS_ACTIVE)) {
        throw new ConflictException(ErrorCode.KH_TNT_1409, "tenant.parent-has-active-children");
      }
      tenant.setStatus(status);
      tenants.save(tenant);
      audit.record(action, "tenant", tenant.getSlug(), null);
    }
    return toView(tenant);
  }

  private Tenant require(UUID id) {
    return tenants
        .findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_TNT_0404, "tenant.not-found"));
  }

  private static String defaultListCode(String slug) {
    return slug + "-" + Year.now(ZoneOffset.UTC).getValue();
  }

  private TenantView toView(Tenant tenant) {
    Tenant parent =
        tenant.getParentTenantId() == null
            ? null
            : tenants.findById(tenant.getParentTenantId()).orElse(null);
    return new TenantView(
        tenant.getId(),
        tenant.getSlug(),
        tenant.getNameI18n(),
        tenant.getType(),
        tenant.getDeployMode(),
        tenant.getStatus(),
        tenant.getCreatedAt(),
        parent == null ? null : parent.getSlug(),
        parent == null ? null : parent.getNameI18n());
  }
}
