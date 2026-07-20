/**
 * Consumer module — verifier/consuming-party registry.
 *
 * <p><b>Responsibilities:</b> register and manage consuming parties that are permitted to verify or
 * consume credentials; enforce per-consumer consumption quotas; scope parties to specific schemas
 * via {@code consuming_party_schema}.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.consumer.api.ConsumingPartyRegistry#ensure} finds or registers a consuming
 * party by code (KH-0.2.1). {@link sy.khatm.platform.consumer.api.ConsumingPartyRegistry
 * #isSchemaAllowed}/{@code #allowSchema} (KH-1.4.3) back {@code credential.domain.CredentialService
 * #consume}'s deny-by-default schema-scoping check via the {@code consuming_party_schema} join
 * table. Real API-key issuance/onboarding beyond the demo seeder stays future work.
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code consuming_party}, {@code consuming_party_schema}
 *
 * <p><b>Status:</b> persistence + find-or-create API (KH-0.2.1); schema scoping enforcement
 * (KH-1.4.3). Console-driven onboarding UI and per-consumer quotas remain future work.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.consumer;
