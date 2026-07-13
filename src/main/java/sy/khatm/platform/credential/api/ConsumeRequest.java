package sy.khatm.platform.credential.api;

/**
 * Request to consume one use of a credential (the double-spend guard path).
 *
 * @param id credential UUID (from {@link IssueResponse#id()})
 * @param consumer identifier of the consuming party (organisation code or device id); stored for
 *     audit; never a real person name (P1 rule)
 * @param idempotencyKey optional caller-supplied key; repeated calls with the same key return the
 *     first outcome without re-running the consume UPDATE
 */
public record ConsumeRequest(String id, String consumer, String idempotencyKey) {}
