package sy.khatm.platform.credential.domain;

import java.time.Instant;

/**
 * Result of {@link CredentialService#issueClaimCode}.
 *
 * <p>{@code code} is shown to the caller exactly once — only its SHA-256 hash is persisted (see
 * {@link ClaimCode}). Public so that same-module callers in other sub-packages (e.g. {@code
 * credential.seed.DemoSeeder}) can use it; Modulith — not Java visibility — keeps it out of reach
 * of other modules, since it lives outside the {@code api} named interface (mirrors the pattern
 * documented on {@link Credential}'s accessors).
 *
 * @param code the raw one-time claim code
 * @param expiresAt when the code stops being claimable
 */
public record ClaimCodeIssued(String code, Instant expiresAt) {}
