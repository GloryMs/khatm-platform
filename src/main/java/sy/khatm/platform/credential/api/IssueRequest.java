package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request to issue a new verifiable credential.
 *
 * <p>Every entry in {@code claims} becomes a salted SD-JWT disclosure (spec FS-0.4 D1) — none of
 * them ever appears as a plaintext value in the stored, signed payload.
 *
 * @param schemaCode credential type code (e.g. {@code CriminalRecordExtract/v1}); defaults to
 *     {@code GenericDocument/v1} if null and {@code schemaId} is also null. Valid values are the
 *     {@code code} field of any entry {@code GET /api/v1/schemas} returns (KH-1.4.3) — an
 *     unrecognized code is not rejected here; {@link
 *     sy.khatm.platform.credential.domain.CredentialService#issue} finds-or-creates a schema for it
 *     at version 1 (KH-0.2.1's stand-in path, still active until every caller supplies {@code
 *     schemaId} instead). Ignored when {@code schemaId} is present.
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
 * @param schemaId internal id of the exact schema (code + version) to issue against — how a caller
 *     that already resolved a specific version (e.g. the console's schema picker, {@code GET
 *     /api/v1/schemas/{id}}) pins issuance to it, rather than always landing on {@code (schemaCode,
 *     version=1)} (KH-2.4-BE follow-up: before this field existed, a schema authored and published
 *     at version 2+ was never reachable from issuance at all — {@code schemaCode} alone can only
 *     ever resolve version 1, see {@link sy.khatm.platform.schema.api.SchemaCatalog#findByCode}).
 *     {@code null} means "no specific version pinned," preserving the {@code schemaCode}-only
 *     quick-issue behavior exactly.
 */
@Schema(name = "IssueRequest", description = "Request to issue a new SD-JWT verifiable credential")
public record IssueRequest(
    String schemaCode,
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String holderRef,
    Integer maxUses,
    Integer validMinutes,
    Map<String, Object> claims,
    List<String> sdFields,
    @Valid AttestationRequest attestation,
    UUID schemaId) {

  /**
   * Convenience constructor for every existing caller that has no specific schema version to pin —
   * equivalent to the canonical constructor with {@code schemaId=null}.
   */
  public IssueRequest(
      String schemaCode,
      String holderRef,
      Integer maxUses,
      Integer validMinutes,
      Map<String, Object> claims,
      List<String> sdFields,
      AttestationRequest attestation) {
    this(schemaCode, holderRef, maxUses, validMinutes, claims, sdFields, attestation, null);
  }
}
