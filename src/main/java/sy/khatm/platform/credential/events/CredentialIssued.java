package sy.khatm.platform.credential.events;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Published the moment a credential is issued — inside {@code CredentialService#issue}'s
 * transaction — then handed to Spring Modulith's transactional outbox and externalized to the
 * {@code khatm.credential.events} Redis Stream (ADR-09). Worker-role consumers read it from the
 * {@code khatm-workers} consumer group.
 *
 * <p><b>P1 / SEC §9:</b> the payload is deliberately proof-shaped — the public opaque {@code ref}
 * and timestamps only. It never carries a claim value, a disclosure, a salt, the signed JWT, or any
 * PII. The same no-disclosure rule that governs logs governs the stream and the DLQ.
 *
 * <p>{@code claimCodeExpiresAt} is the one piece of claim-code metadata a future consumer might act
 * on (e.g. nudging a wallet before a code lapses). It is {@code null} for a bare issuance that
 * creates no claim code — {@code CredentialService#issue} does not create one itself; {@code
 * CredentialService#issueClaimCode} is a separate call. It is non-null only once some future flow
 * bundles claim-code creation into issuance.
 *
 * @param ref the credential's public opaque ref (never its DB id)
 * @param claimCodeExpiresAt when a claim code issued alongside this credential expires, or {@code
 *     null} if issuance created no claim code
 * @param occurredAt when the issuance happened (UTC)
 * @param tenantId the issuing tenant (KH-2.1, spec FS-2.1 D5) — no consumer reads this yet (no
 *     {@code StreamEventHandler} handles this event type today), added so a future one can restore
 *     {@code shared.TenantContext} the same way {@code StatusListChangedHandler} does, without a
 *     later non-additive payload change
 */
@Externalized("khatm.credential.events")
public record CredentialIssued(
    String ref, Instant claimCodeExpiresAt, Instant occurredAt, UUID tenantId) {}
