package sy.khatm.platform.rbac.security;

import java.util.Set;

/**
 * The canonical, deny-by-default scope catalog (spec FS-2.2 D1) — the platform's entire
 * authorization vocabulary. {@code admin} (the KH-0.6b/MVP coarse stand-in) is retired outright
 * (spec V3, clean cut, no coexistence) — grep for the literal string {@code "admin"} as a {@link
 * ScopeGuard#requireScope}/{@link ScopeGuard#requireAnyScope} argument anywhere under {@code
 * src/main/java} should return nothing; {@code rbac.security.LegacyAdminScopeAbsenceTest} pins
 * this.
 *
 * <p>Every scope here maps to exactly one family of endpoints (spec D2's mapping, re-verified
 * against the live endpoint inventory this session): {@link #ISSUE}/{@link #VERIFY}/{@link
 * #CONSUME}/{@link #REVOKE} are the existing per-action scopes (unchanged by this session — they
 * predate the coarse {@code admin} scope and were never part of the problem it replaced); {@link
 * #SCHEMA_MANAGE} gates schema authoring writes ({@code POST}/{@code PUT /api/v1/schemas/**}) —
 * reads stay open to any action-scope holder (spec V2); {@link #CONSUMER_MANAGE} gates the
 * consuming-party admin plane, including its key-mint endpoint; {@link #KEY_MANAGE} is reserved for
 * signing-key lifecycle surfaces (today: the read-only {@code GET /api/v1/admin/signing-keys}
 * status view; KH-2.3's rotation endpoint will reuse it); {@link #TENANT_ADMIN} gates a tenant's
 * own operational API-key management (spec V4 — deliberately distinct from {@link #KEY_MANAGE},
 * which is signing keys only); {@link #PLATFORM_ADMIN} gates the cross-tenant {@code
 * /api/v1/admin/tenants/**} plane exclusively (spec D2) and is the sole scope {@code
 * shared.OnBehalfOfExecutor} accepts.
 */
final class ScopeRegistry {

  private ScopeRegistry() {}

  static final String ISSUE = "issue";
  static final String VERIFY = "verify";
  static final String CONSUME = "consume";
  static final String REVOKE = "revoke";
  static final String SCHEMA_MANAGE = "schema:manage";
  static final String CONSUMER_MANAGE = "consumer:manage";
  static final String KEY_MANAGE = "key:manage";
  static final String TENANT_ADMIN = "tenant:admin";
  static final String PLATFORM_ADMIN = "platform:admin";

  /** The complete registry (spec D1) — every legal scope value, nothing else. */
  static final Set<String> ALL =
      Set.of(
          ISSUE,
          VERIFY,
          CONSUME,
          REVOKE,
          SCHEMA_MANAGE,
          CONSUMER_MANAGE,
          KEY_MANAGE,
          TENANT_ADMIN,
          PLATFORM_ADMIN);

  /** The four action scopes schema READ endpoints accept in addition to {@link #SCHEMA_MANAGE}. */
  static final Set<String> SCHEMA_READ_SCOPES =
      Set.of(ISSUE, VERIFY, CONSUME, REVOKE, SCHEMA_MANAGE);
}
