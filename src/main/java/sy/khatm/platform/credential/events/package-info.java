/**
 * Domain events published by the credential module and externalized to Redis Streams via Spring
 * Modulith's transactional outbox (ADR-09).
 *
 * <p><b>Published:</b> {@link sy.khatm.platform.credential.events.CredentialIssued} — fired inside
 * {@code CredentialService#issue}'s transaction, routed to the {@code khatm.credential.events}
 * stream.
 *
 * <p>Every event payload here is proof-shaped: refs and timestamps only, never claim values,
 * disclosures, salts, or PII (SEC §9 — streams and the DLQ obey the same no-disclosure rule as
 * logs).
 */
package sy.khatm.platform.credential.events;
