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
 * consuming_party.api_key_hash} stand-in the same migration drops), {@code user_totp_recovery_code}
 * (new in {@code V13__totp_2fa.sql}, KH-2.2c — {@code app_user} itself gained three columns in the
 * same migration for the TOTP secret/enrollment/confirmation state).
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
 *
 * <p><b>KH-2.2a — scope registry (spec FS-2.2 D1/D2/D3):</b> the coarse {@code admin} scope is
 * retired outright (clean cut, spec V3) and replaced by {@link
 * sy.khatm.platform.rbac.security.ScopeRegistry}'s nine granular scopes — {@code issue}/{@code
 * verify}/{@code consume}/{@code revoke} unchanged, plus {@code schema:manage}/{@code
 * consumer:manage}/{@code key:manage}/{@code tenant:admin}/{@code platform:admin} replacing every
 * {@code /api/v1/admin/**} use of {@code admin}. {@code V10__scope_registry_rescope.sql} rewrites
 * the three seeded roles' {@code scopes} accordingly. {@code web.AuthController#createApiKey}'s
 * explicit-{@code tenantId} branch (minting a key for a tenant other than the caller's own) now
 * requires {@code platform:admin} specifically, enforced by {@code shared.OnBehalfOfExecutor} — the
 * gap this closes: before this session, any caller holding the endpoint's baseline scope could name
 * an arbitrary foreign {@code tenantId} with no cross-tenant check at all.
 *
 * <p><b>KH-2.6b — org:admin on-behalf-of plane (spec FS-2.5 §3/§4):</b> {@link
 * sy.khatm.platform.rbac.domain.OrgAdminService}, under {@code /api/v1/org/**} ({@code
 * rbac.web.OrgAdminController}) — the fourth scope's home, alongside the fixed role catalog's
 * fourth entry ({@code ORG_ADMIN}, carrying only {@code org:admin}). Lives here for the identical
 * Modulith-cycle reason {@link sy.khatm.platform.rbac.domain.TenantProvisioningService} already
 * does: child user management touches this module's own {@code app_user}/{@code role} tables.
 * Reuses {@code schema :: api}'s {@code SchemaCatalog#listAll} (child schema view, read-only) and
 * {@code tenant :: api}'s {@code TenantAdmin#suspend}/{@code #activate} (child suspend/reactivate)
 * unchanged — no new cross-module edges beyond the ones this module already declared. {@code
 * shared.OnBehalfOfExecutor#runAsChildOrg} is the org-plane sibling of {@code #runAsTenant}, same
 * shape, gated on {@code org:admin} instead of {@code platform:admin}; {@code
 * TotpEnrollmentEnforcementFilter}'s mandatory-scope set now includes {@code org:admin} too.
 *
 * <p><b>KH-2.2c — TOTP second factor (spec FS-2.2 V1):</b> self-service enrollment ({@code POST
 * /users/me/totp/enroll}/{@code /confirm}, {@link sy.khatm.platform.rbac.domain.TotpService}),
 * AES-256-GCM secret encryption at rest ({@link
 * sy.khatm.platform.rbac.domain.TotpSecretEncryptionService} — a second, dedicated-key sibling of
 * {@code credential.domain.ClaimsEncryptionService}, not a cross-module reuse of that
 * module-private class), a login challenge for accounts with an active enrollment ({@code
 * AuthService#login} returns {@code LoginOutcome.TotpChallenge} instead of establishing a session;
 * {@code POST /api/v1/auth/totp} completes it with a code or a one-time recovery code, rate-limited
 * identically to the password step), and a mandatory-enrollment wall ({@code
 * security.TotpEnrollmentEnforcementFilter}, the exact {@code PasswordChangeEnforcementFilter}
 * shape) for any session holding {@code revoke}/{@code tenant:admin}/{@code platform:admin}/{@code
 * key:manage} with no active TOTP. Both reset paths (self-tenant {@code tenant:admin}, and
 * cross-tenant {@code platform:admin} via {@code TenantProvisioningService#resetTotpInTenant} +
 * {@code OnBehalfOfExecutor}) clear a user's enrollment administratively.
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
