/**
 * Consumer module — verifier/consuming-party registry.
 *
 * <p><b>Responsibilities:</b> register and manage consuming parties that are permitted to verify or
 * consume credentials; enforce per-consumer consumption quotas; scope parties to specific schemas
 * via {@code consuming_party_schema}.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.consumer.api.ConsumingPartyRegistry#ensure} finds or registers a consuming
 * party by code (KH-0.2.1). Real API-key issuance/onboarding is KH-1.4.3.
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code consuming_party}, {@code consuming_party_schema}
 *
 * <p><b>Status:</b> minimal persistence + find-or-create API (KH-0.2.1); onboarding, quotas, schema
 * scoping deferred to KH-1.4.3.
 */
package sy.khatm.platform.consumer;
