package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Human attestation of a scanned document, submitted alongside {@link IssueRequest} for a schema
 * with {@code requires_attestation=true} (KH-2.4, spec FS-2.4 item 2 — the non-automated
 * issuer-portal flow).
 *
 * <p>The attesting operator is always the authenticated principal — never a request field; {@code
 * shared.audit.AuditService#record} infers it from {@code SecurityContextHolder} the same way every
 * other audit row does.
 *
 * @param note optional free-text note from the attesting operator, at most 500 characters; recorded
 *     verbatim in the {@code SCAN_ATTESTED} audit line's {@code detail.note} — never a claim value
 *     (P1/SEC §9)
 */
@Schema(
    name = "AttestationRequest",
    description =
        "Human attestation of a scanned document, required by schemas with"
            + " requires_attestation=true")
public record AttestationRequest(@Size(max = 500) String note) {}
