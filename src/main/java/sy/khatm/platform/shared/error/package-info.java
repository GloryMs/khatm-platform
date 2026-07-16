/**
 * The platform's single error-handling vocabulary (CLAUDE.md work rule 3, spec FS-0.6a).
 *
 * <p>{@link sy.khatm.platform.shared.error.KhatmException} and its six subtypes are the only
 * exceptions any module should throw for a business/request error; {@link
 * sy.khatm.platform.shared.error.ErrorCode} is the registry {@code GlobalExceptionHandler}
 * (module-private, in {@code shared :: web}) resolves them against. {@link
 * sy.khatm.platform.shared.error.VerifyReason} is the separate, non-exception vocabulary for
 * credential-verification domain results (spec FS-0.6a D1/D2) — a verification failure is never a
 * thrown exception.
 *
 * <p>A {@code @NamedInterface} ({@code shared :: error}) because other modules (currently only
 * {@code credential}) need to throw these exceptions and use {@code VerifyReason} directly — unlike
 * most of {@code shared}'s subpackages, this one is a deliberate cross-module surface, not an
 * accident of the "open root package" convention.
 */
@org.springframework.modulith.NamedInterface("error")
package sy.khatm.platform.shared.error;
