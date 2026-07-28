/**
 * Consumer module — verifier/consuming-party registry.
 *
 * <p><b>Responsibilities:</b> register and manage consuming parties that are permitted to verify or
 * consume credentials; enforce per-consumer consumption quotas; scope parties to specific schemas
 * via {@code consuming_party_schema}.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package. {@link
 * sy.khatm.platform.consumer.api.ConsumingPartyRegistry} is the runtime consume-path SPI: {@code
 * #ensure} finds or registers a party by code (KH-0.2.1); {@code #isSchemaAllowed}/{@code
 * #allowSchema} (KH-1.4.3) back {@code credential.domain.CredentialService#consume}'s
 * deny-by-default schema-scoping check via the {@code consuming_party_schema} join table; {@code
 * #isActive} (KH-1.4.4 D4) lets {@code rbac}'s API-key verification reject a {@code SUSPENDED}
 * party's keys. {@link sy.khatm.platform.consumer.api.ConsumingPartyAdmin} (KH-1.4.4) is the admin
 * plane behind {@code /api/v1/admin/consuming-parties} (list/create/suspend/activate + schema
 * allowlist); {@code rbac.web} depends on its {@code #get} to validate a party before minting a
 * {@code CONSUMING_PARTY} key.
 *
 * <p><b>Admin plane (KH-1.4.4, module-private {@code consumer.domain.ConsumingPartyAdminService},
 * {@code consumer.web.ConsumingPartyAdminController}):</b> {@code GET /api/v1/admin/consuming-
 * parties} (list), {@code POST} (register — idempotent by deterministic id, duplicate code →
 * KH-CNS-0409), {@code POST /{id}/suspend} and {@code /activate}, {@code POST
 * /{id}/allowed-schemas} / {@code DELETE /{id}/allowed-schemas/{schemaId}}. Key minting lives in
 * {@code rbac.web} (only {@code rbac} may create {@code api_key} rows, and {@code consumer}
 * depending on {@code rbac} would cycle). All guarded by the {@code consumer:manage} scope (spec
 * FS-2.2 D2).
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code consuming_party} ({@code code} column added by {@code
 * V5__consuming_party_code.sql}, KH-1.4.4), {@code consuming_party_schema}
 *
 * <p><b>Cross-module dependencies:</b> {@code shared} (open root), {@code shared :: error}, {@code
 * shared :: audit}; {@code schema :: api} (KH-1.4.4 — {@code ConsumingPartyAdminService} validates
 * a schema exists before allowlisting it, and resolves allowlisted schema codes for the admin
 * view).
 *
 * <p><b>Status:</b> persistence + find-or-create API (KH-0.2.1); schema scoping enforcement
 * (KH-1.4.3); admin plane + suspended-party enforcement + find-or-create race closure (KH-1.4.4).
 * Per-consumer quotas and per-party rate limits remain future work.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.consumer;
