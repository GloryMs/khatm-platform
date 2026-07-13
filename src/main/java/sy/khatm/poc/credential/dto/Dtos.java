package sy.khatm.poc.credential.dto;

import java.util.Map;

public final class Dtos {
    private Dtos() {}

    public record IssueRequest(
            String schemaCode,       // e.g. CriminalRecordExtract/v1
            String holderRef,        // pseudonymous holder id
            Integer maxUses,         // default 1
            Integer validMinutes,    // default 60
            Map<String, Object> claims // optional extra claims (name, etc.)
    ) {}

    public record IssueResponse(String id, String ref, String jwt) {}

    public record VerifyRequest(String jwt) {}

    public record VerifyResponse(
            boolean valid,
            String reason,
            Map<String, Object> claims,
            Integer usesRemaining,
            boolean revoked
    ) {}

    public record ConsumeRequest(String id, String consumer, String idempotencyKey) {}

    public record ConsumeResponse(boolean consumed, String reason, Integer usesRemaining) {}
}
