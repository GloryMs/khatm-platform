package sy.khatm.platform.credential.domain;

import java.util.UUID;
import sy.khatm.platform.credential.api.VerifyResponse;

/**
 * Result of {@link CredentialService#verifyOutcome}, pairing the public wire-shape {@link
 * VerifyResponse} with the credential's own issuing tenant — resolved once, inside {@code verify}'s
 * single pass over the presentation, rather than re-derived afterward from the response alone
 * (fixing the audit-attribution bug where {@code credential.web.CredentialController#verify} always
 * attributed {@code CREDENTIAL_VERIFY_OK}/{@code CREDENTIAL_VERIFY_FAILED} to the platform default
 * tenant instead of the credential's real one).
 *
 * <p>Public so that {@code credential.web.CredentialController} (a different sub-package in the
 * same module) can read it; Modulith — not Java visibility — keeps it out of reach of other
 * modules, mirroring {@link ClaimRedeemResult}'s existing rationale. Never serialized directly —
 * the controller unwraps {@link #response()} for the HTTP body and uses {@link #tenantId()}/{@link
 * #tenantSlug()} only to scope the audit write.
 *
 * @param response the public verification result, unchanged from {@link VerifyResponse}'s own
 *     contract
 * @param tenantId the credential's issuing tenant, or {@code null} on every early-exit branch that
 *     never resolved a credential row at all (malformed presentation, bad signature, unknown {@code
 *     kid}, unknown {@code ref}) — there is genuinely no tenant to attribute to there
 * @param tenantSlug the issuing tenant's slug, {@code null} exactly when {@link #tenantId} is
 *     {@code null}
 */
public record VerifyOutcome(VerifyResponse response, UUID tenantId, String tenantSlug) {}
