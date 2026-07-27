/**
 * RBAC module — console auth, API keys, and role-based access control (spec FS-0.6b).
 *
 * <p><b>Responsibilities:</b> console-session login/logout (username/password, argon2id, Redis-
 * backed Spring Session, D1), a temporary Redis-TTL lockout counter independent of the
 * administrative {@code LOCKED} status (D6), programmatic API keys (create/revoke/verify, {@code
 * khk_<env>_<prefix>.<secret>}, SHA-256, D2–D4), and the Spring Security filter chain that gates
 * every non-public endpoint by scope and, where the spec requires it, actor kind (D9 — {@code
 * /verify} and the JWKS endpoint are the only two endpoints that stay open).
 *
 * <p><b>Exposed API:</b> {@code api/} — {@link sy.khatm.platform.rbac.api.CurrentActor} + {@link
 * sy.khatm.platform.rbac.api.CurrentActorResolver}, a way for another module to ask "who is making
 * this call" without depending on Spring Security types directly — {@code
 * credential.domain.CredentialService#consume} (KH-1.4.3, spec §9) is the first real consumer,
 * reading {@link sy.khatm.platform.rbac.api.CurrentActor#ownerId()} to enforce {@code
 * consuming_party_schema} scoping. {@link sy.khatm.platform.rbac.api.ApiKeyOwnerLookup} (spec
 * FS-1.5.4 D2, KH-1.1.5-BE) is the batch counterpart for a <em>historical</em> {@code
 * audit_log.actor_id} — {@code CurrentActorResolver} only ever resolves the current request's actor
 * — used by {@code credential.web}'s activity/consuming-party-stats endpoints to attribute a past
 * {@code CREDENTIAL_CONSUMED}/{@code CONSUME_SCHEMA_DENIED} row to its owning consuming party.
 * Backed by the plain {@code ApiKeyRepository#findAllById} already inherited from Spring Data — no
 * new query, no new column.
 *
 * <p><b>Published events:</b> none.
 *
 * <p><b>Tables owned:</b> {@code app_user}, {@code role}, {@code user_role} (seeded by {@code
 * V1__baseline.sql}, Java persistence added in this module for the first time in KH-0.6b), {@code
 * api_key} (new in {@code V2__auth_api_keys.sql}, spec §4 — replaces the {@code
 * consuming_party.api_key_hash} stand-in the same migration drops).
 *
 * <p><b>Cross-module dependencies:</b> {@code shared} (its open root package — {@link
 * sy.khatm.platform.shared.TenantContext}, {@link sy.khatm.platform.shared.Uuidv7}, {@link
 * sy.khatm.platform.shared.LocalizedText}); {@code shared :: error} ({@code KhatmException}
 * subtypes — {@code AuthenticationException}/{@code AuthorizationException}, finally thrown as of
 * this module); {@code shared :: audit} ({@code AuditService} — the seven {@code AUTH_*}/{@code
 * API_KEY_*}/{@code USER_CREATED} actions this module records); {@code shared :: web} ({@code
 * ErrorEnvelope}, referenced from this module's OpenAPI annotations and built directly by {@code
 * security.SecurityEnvelopeWriter} for the pre-{@code DispatcherServlet} filter-chain denials);
 * {@code consumer :: api} ({@code ConsumingPartyRegistry} — {@code seed.DemoApiKeySeeder}'s {@code
 * local}/{@code dev}-only demo {@code CONSUMING_PARTY} API key needs a real consuming party to own
 * it, and — KH-1.4.3 — allowlists that party for the demo schema); {@code schema :: api} ({@code
 * SchemaCatalog#listAll}, KH-1.4.3 — {@code seed.DemoApiKeySeeder} resolves the demo schema's id by
 * code to allowlist it, same local/dev-only seeder); {@code tenant :: api} ({@code
 * TenantDirectory}, KH-2.1 spec FS-2.1 D1/D7 — {@code security.TenantContextFilter} resolves a
 * principal's tenant and enforces suspension; {@code domain.ApiKeyService#verify}/{@code
 * domain.AuthService#login} check the same tenant's active status directly, mirroring the KH-1.4.3
 * suspended-consuming-party check).
 *
 * <p><b>KH-2.1 Part B:</b> {@code api_key} is Row-Level-Security-protected like every other
 * business table, and {@code domain.ApiKeyService#verify} is, by construction, a lookup with no
 * tenant known yet — resolving the tenant is the whole point, so it cannot rely on {@link
 * sy.khatm.platform.shared.TenantContext}'s ambient value the way this module's other
 * {@code @Transactional} methods do. It now runs under {@code shared.SystemAccessExecutor} (spec
 * D5), the same mechanism the JWKS/status-list/redeem/verify anonymous-read paths use. {@code
 * domain.ApiKeyService#create(..., UUID tenantId)} (the tenant-admin-plane overload that mints a
 * key for a tenant other than the caller's own) sets {@link sy.khatm.platform.shared.TenantContext}
 * explicitly to the target tenant around its insert, for the same reason {@code
 * tenant.domain.TenantAdminService#create} does.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "shared",
      "shared :: error",
      "shared :: audit",
      "shared :: web",
      "consumer :: api",
      "schema :: api",
      "tenant :: api"
    })
package sy.khatm.platform.rbac;
