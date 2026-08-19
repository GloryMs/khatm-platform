package sy.khatm.platform.rbac.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaSummary;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.OnBehalfOfExecutor;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.web.StatsWindow;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * The {@code org:admin} on-behalf-of plane (KH-2.6b, spec FS-2.5 §3/§4) — the four named operations
 * a parent tenant's {@code org:admin} holder may perform on its <em>direct</em> children, plus the
 * aggregated proofs-not-content report over the full descendant subtree. Lives in {@code rbac} (not
 * {@code tenant}), the same Modulith-cycle avoidance {@link TenantProvisioningService} already
 * established for the {@code platform:admin} on-behalf-of plane: child user management touches
 * {@code rbac}-owned {@code app_user}/{@code role} rows, and {@code tenant → rbac} would cycle
 * against the existing {@code rbac → tenant :: api} edge.
 *
 * <p><b>Direct children only, at every boundary</b> (spec §7's ripple default): {@link
 * #requireDirectChild} is the single gate every child-targeted method routes through, resolving the
 * path {@code id} against {@link TenantDirectory#directChildren} of the caller's own ambient tenant
 * — a grandchild, a sibling subtree, or an unrelated tenant collapses to the same {@code
 * KH-ORG-0404} a genuinely nonexistent id would (anti-enumeration, spec {@code ErrorCode.
 * KH_ORG_0404}'s own Javadoc). {@link #report} is the one exception by design — spec §7 makes the
 * aggregated report transitive over the <em>full</em> subtree (children and grandchildren), unlike
 * every administrative operation here.
 *
 * <p><b>No privilege beyond entity management</b> (spec §3's explicit stance — "إدارة الكيان لا
 * محتواه"): this class never reads a child's credentials, proofs, or detailed audit trail, and
 * never touches its signing keys — the four methods here reuse exactly the same {@link
 * UserAdminService}/{@link SchemaCatalog}/{@link TenantAdmin} surfaces a local {@code tenant:admin}
 * already has, routed on behalf of the child rather than granting anything new. Whatever
 * constraints those surfaces already apply to a local {@code tenant:admin} caller (e.g. {@link
 * UserAdminService#create}'s role-catalog validation) apply identically here, unchanged.
 *
 * <p>Every child-targeted mutation is audited on both sides "for free," by construction: {@link
 * OnBehalfOfExecutor#runAsChildOrg} writes the parent-side {@link AuditAction#ORG_ON_BEHALF_OF}
 * marker before switching {@code TenantContext}, and the wrapped action's own pre-existing audit
 * write (e.g. {@code USER_CREATED}, {@code TENANT_SUSPENDED}) then lands under the child's own
 * ambient tenant once switched — the same mechanism {@link TenantProvisioningService} already
 * relies on for the {@code platform:admin} plane. A read-only call (listing children, listing a
 * child's users, viewing its schemas) gets only the parent-side marker, matching the platform-wide
 * convention that reads are not separately audited.
 */
@Service
public class OrgAdminService {

  private final TenantDirectory tenants;
  private final TenantAdmin tenantAdmin;
  private final SchemaCatalog schemas;
  private final OnBehalfOfExecutor onBehalfOf;
  private final SystemAccessExecutor systemAccess;
  private final UserAdminService userAdmin;
  private final AuditService audit;

  public OrgAdminService(
      TenantDirectory tenants,
      TenantAdmin tenantAdmin,
      SchemaCatalog schemas,
      OnBehalfOfExecutor onBehalfOf,
      SystemAccessExecutor systemAccess,
      UserAdminService userAdmin,
      AuditService audit) {
    this.tenants = tenants;
    this.tenantAdmin = tenantAdmin;
    this.schemas = schemas;
    this.onBehalfOf = onBehalfOf;
    this.systemAccess = systemAccess;
    this.userAdmin = userAdmin;
    this.audit = audit;
  }

  /**
   * The caller's own tenant's direct children and their statuses (spec §3, operation 1). Reads the
   * {@code tenant} table only (excluded from RLS — spec FS-2.1 D2), so needs no on-behalf-of
   * context switch and is not itself audited (the platform-wide convention: reads are not audited).
   *
   * @return every direct child of the caller's own tenant, in no particular order
   */
  public List<TenantRef> listChildren() {
    return tenants.directChildren(TenantContext.current());
  }

  /**
   * A direct child's users (spec §3, operation 2 — "list").
   *
   * @param childId the target child tenant
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public List<UserSummary> listChildUsers(UUID childId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), userAdmin::list);
  }

  /**
   * Create a user in a direct child (spec §3, operation 2 — "create"), the same shape a local
   * {@code tenant:admin} creating a user in their own tenant already gets — no additional
   * privilege.
   *
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public CreatedUser createChildUser(
      UUID childId, String username, LocalizedText displayNameI18n, Set<String> roleCodes) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(
        child.id(), child.slug(), () -> userAdmin.create(username, displayNameI18n, roleCodes));
  }

  /**
   * Disable a user in a direct child (spec §3, operation 2 — "disable").
   *
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public UserSummary disableChildUser(UUID childId, UUID userId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), () -> userAdmin.disable(userId));
  }

  /**
   * Reset a user's password in a direct child (spec §3, operation 2 — "reset").
   *
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public CreatedUser resetChildUserPassword(UUID childId, UUID userId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(
        child.id(), child.slug(), () -> userAdmin.resetPassword(userId));
  }

  /**
   * A direct child's schemas, read-only (spec §3, operation 3).
   *
   * @param childId the target child tenant
   * @param status optional lifecycle-status filter, same as {@link SchemaCatalog#listAll}
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public List<SchemaSummary> listChildSchemas(UUID childId, String status) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), () -> schemas.listAll(status));
  }

  /**
   * Suspend a direct child tenant (spec §3, operation 4 — {@code tenant:admin} degree, never a
   * delete). Reuses {@link TenantAdmin#suspend}'s existing no-cascade guard unchanged.
   *
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public TenantView suspendChild(UUID childId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(
        child.id(), child.slug(), () -> tenantAdmin.suspend(child.id()));
  }

  /**
   * Reactivate a direct child tenant (spec §3, operation 4).
   *
   * @throws NotFoundException {@code KH-ORG-0404} if {@code childId} is not a direct child of the
   *     caller's own tenant
   */
  public TenantView activateChild(UUID childId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(
        child.id(), child.slug(), () -> tenantAdmin.activate(child.id()));
  }

  /**
   * The aggregated proofs-not-content report over the caller's <em>full</em> descendant subtree
   * (spec §4/§7 — transitive, children and grandchildren, unlike every operation above). Each count
   * is read under {@link SystemAccessExecutor#runAsSystem} (spec §4's explicit mandate — the one
   * audited path across the org boundary for this, never an RLS change); {@code TenantContext}
   * itself is never switched (system access only bypasses the RLS predicate for the duration of
   * each count's own transaction), so this method needs no on-behalf-of context handling and
   * records {@link AuditAction#ORG_REPORT_VIEWED} directly under the caller's own ambient (parent)
   * tenant.
   *
   * @param from inclusive start of the window
   * @param to exclusive end of the window
   * @return one entry per descendant (any depth) plus the whole-subtree rollup
   */
  public OrgReportView report(Instant from, Instant to) {
    List<TenantRef> subtree = tenants.descendants(TenantContext.current());
    List<OrgReportEntry> entries = new ArrayList<>();
    OrgReportCounters rollup = OrgReportCounters.ZERO;
    for (TenantRef descendant : subtree) {
      OrgReportCounters counters = countersFor(descendant.id(), from, to);
      entries.add(
          new OrgReportEntry(descendant.id(), descendant.slug(), descendant.nameI18n(), counters));
      rollup = rollup.plus(counters);
    }
    audit.record(
        AuditAction.ORG_REPORT_VIEWED,
        "tenant",
        null,
        Map.of(
            "descendantCount", subtree.size(),
            "from", from.toString(),
            "to", to.toString()));
    return new OrgReportView(new StatsWindow(from, to), entries, rollup);
  }

  private OrgReportCounters countersFor(UUID tenantId, Instant from, Instant to) {
    Map<String, Long> counts =
        systemAccess.runAsSystem(() -> audit.countActionsInWindow(tenantId, from, to));
    return new OrgReportCounters(
        counts.getOrDefault(AuditAction.CREDENTIAL_ISSUED.name(), 0L),
        counts.getOrDefault(AuditAction.CREDENTIAL_VERIFY_OK.name(), 0L),
        counts.getOrDefault(AuditAction.CREDENTIAL_VERIFY_FAILED.name(), 0L),
        counts.getOrDefault(AuditAction.CREDENTIAL_CONSUMED.name(), 0L),
        counts.getOrDefault(AuditAction.CREDENTIAL_REVOKED.name(), 0L));
  }

  private TenantRef requireDirectChild(UUID childId) {
    return tenants.directChildren(TenantContext.current()).stream()
        .filter(child -> child.id().equals(childId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_ORG_0404, "org.child-not-found"));
  }
}
