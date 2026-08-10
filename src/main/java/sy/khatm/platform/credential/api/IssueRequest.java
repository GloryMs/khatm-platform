package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * Request to issue a new verifiable credential.
 *
 * <p>Every entry in {@code claims} becomes a salted SD-JWT disclosure (spec FS-0.4 D1) — none of
 * them ever appears as a plaintext value in the stored, signed payload.
 *
 * @param schemaCode credential type code (e.g. {@code CriminalRecordExtract/v1}); defaults to
 *     {@code GenericDocument/v1} if null. Valid values are the {@code code} field of any entry
 *     {@code GET /api/v1/schemas} returns (KH-1.4.3) — an unrecognized code is not rejected here;
 *     {@link sy.khatm.platform.credential.domain.CredentialService#issue} finds-or-creates a schema
 *     for it (KH-0.2.1's stand-in path, still active until schema authoring is console-driven)
 * @param holderRef pseudonymous holder identifier; never a real name or national ID. Mandatory
 *     (spec FS-0.6a §5 DoD #3 exercises this field's Bean Validation) — a credential must always
 *     name who it was issued to, even pseudonymously.
 * @param maxUses maximum number of times this credential may be consumed; defaults to 1
 * @param validMinutes validity window in minutes from issuance; defaults to 60
 * @param claims claim name/value pairs to disclose selectively (document-specific metadata; no PII
 *     per P1 rule) — every one becomes an SD-JWT disclosure, no exceptions (D1)
 * @param sdFields names from {@code claims} the holder is permitted to withhold at presentation
 *     time (spec FS-0.4 D2); any {@code claims} key not listed here is mandatory to disclose in
 *     every presentation. {@code null} means every claim is withholdable (used when the caller has
 *     no mandatory/optional distinction to express yet)
 * @param attestation required when the resolved schema has {@code requires_attestation=true},
 *     forbidden otherwise (KH-2.4, spec FS-2.4 item 2, deny-by-default in both directions — {@code
 *     CredentialService#issue} rejects a mismatch as {@code KH-ATT-0400}/{@code KH-ATT-0401} rather
 *     than silently ignoring it); {@code null} for every schema that does not require it
 */
@Schema(name = "IssueRequest", description = "Request to issue a new SD-JWT verifiable credential")
public record IssueRequest(
    String schemaCode,
    @NotBlank String holderRef,
    Integer maxUses,
    Integer validMinutes,
    Map<String, Object> claims,
    List<String> sdFields,
    @Valid AttestationRequest attestation) {}
