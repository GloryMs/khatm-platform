package sy.khatm.platform.credential.api;

import java.util.Map;

/**
 * Request to issue a new verifiable credential.
 *
 * @param schemaCode credential type code (e.g. {@code CriminalRecordExtract/v1}); defaults to
 *     {@code GenericDocument/v1} if null
 * @param holderRef pseudonymous holder identifier; never a real name or national ID
 * @param maxUses maximum number of times this credential may be consumed; defaults to 1
 * @param validMinutes validity window in minutes from issuance; defaults to 60
 * @param claims optional additional JWT claims (document-specific metadata; no PII per P1 rule)
 */
public record IssueRequest(
    String schemaCode,
    String holderRef,
    Integer maxUses,
    Integer validMinutes,
    Map<String, Object> claims) {}
