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
 * sy.khatm.platform.rbac.api.CurrentActorResolver}, a forward-looking way for a future module to
 * ask "who is making this call" (KH-1.4.3's {@code allowed_schemas} enforcement will be the first
 * real consumer, spec §9) without depending on Spring Security types directly.
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
 * it).
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "shared",
      "shared :: error",
      "shared :: audit",
      "shared :: web",
      "consumer :: api"
    })
package sy.khatm.platform.rbac;
