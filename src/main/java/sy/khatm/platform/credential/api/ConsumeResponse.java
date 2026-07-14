package sy.khatm.platform.credential.api;

/**
 * Result of a credential consumption attempt.
 *
 * @param consumed {@code true} if this call decremented the use counter
 * @param reason machine-readable reason code (e.g. {@code consumed}, {@code already_consumed},
 *     {@code not_consumable}, {@code idempotent_replay})
 * @param usesRemaining use count after this operation; {@code null} if the credential was not found
 */
public record ConsumeResponse(boolean consumed, String reason, Integer usesRemaining) {}
