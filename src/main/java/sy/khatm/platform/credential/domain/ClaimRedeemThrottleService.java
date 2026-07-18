package sy.khatm.platform.credential.domain;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * Per-IP fixed-window throttle for {@code POST /api/v1/claims/redeem} (spec FS-1.2.1 D6) — makes
 * guessing claim codes economically unattractive without a general platform-wide rate-limiting
 * layer, which would be premature ahead of any other endpoint needing one (spec §1, out of scope).
 *
 * <p>Same Redis counter shape as {@code rbac.domain.AuthService}'s login-lockout counter: {@code
 * khatm:claims:redeem:throttle:{ip}} counts attempts with a fixed window from the <em>first</em>
 * attempt (the TTL is set once, on the transition from 0 to 1, and never refreshed by later
 * attempts — otherwise a slow drip could keep a window open indefinitely). Every attempt counts
 * toward the window, successful or not — the point is bounding total attempts per address, not just
 * failures. {@code X-Forwarded-For} is deliberately not read (spec D6): there is no reverse proxy
 * in front of this platform locally, so trusting that header today would let a caller spoof it to
 * reset their own budget; revisit when staging sits behind one.
 *
 * <p><b>KH_CLM_0429 rides on {@link ValidationException}</b> — a deliberate choice, not a semantic
 * "malformed request." None of CLAUDE.md's six {@code KhatmException} subtypes was written with
 * HTTP 429 in mind (the {@code rbac} module's own analogous lockout counter reuses {@code
 * AuthenticationException}/401 instead of introducing a new HTTP status). Every subtype's Javadoc
 * says its usual status is "typical," not exclusive — {@link ErrorCode#httpStatus()} alone decides
 * the actual response — and adding a seventh subtype would silently invalidate CLAUDE.md's own
 * documented "six subtypes" list without an explicit approved instruction to change it (session
 * protocol). {@code ValidationException}'s existing description ("the request itself... fails
 * validation, independent of Bean Validation") is the least-wrong fit of the six: exceeding a
 * request-rate policy is a request-level rejection in the same family as a Bean Validation failure,
 * and — unlike every other subtype — it doesn't misrepresent this call as having a session, a
 * scope, or a resource conflict it never had. Flagged for the PR reviewer as a judgment call, not a
 * spec requirement.
 *
 * <p>This class is module-private (Modulith-enforced, not Java visibility — {@code
 * credential.web.ClaimController} in a different sub-package of the same module calls it, mirroring
 * {@link CredentialService}'s existing rationale).
 */
@Component
public class ClaimRedeemThrottleService {

  private static final String KEY_PREFIX = "khatm:claims:redeem:throttle:";

  private final StringRedisTemplate redis;
  private final AuditService audit;
  private final int maxAttempts;
  private final Duration window;

  public ClaimRedeemThrottleService(
      StringRedisTemplate redis,
      AuditService audit,
      @Value("${khatm.claims.redeem.throttle.max-attempts:10}") int maxAttempts,
      @Value("${khatm.claims.redeem.throttle.window:1m}") Duration window) {
    this.redis = redis;
    this.audit = audit;
    this.maxAttempts = maxAttempts;
    this.window = window;
  }

  /**
   * Count one redeem attempt from {@code clientIp} and reject it if the fixed window's budget is
   * already exhausted.
   *
   * @param clientIp the caller's address ({@code HttpServletRequest#getRemoteAddr()} — no proxy
   *     header trust, see class Javadoc)
   * @throws ValidationException {@link ErrorCode#KH_CLM_0429} once the window's attempt budget is
   *     exceeded; also records {@link AuditAction#CLAIM_REDEEM_THROTTLED} (IP + count — spec D7,
   *     the one failure flavor of this endpoint that IS audited individually)
   */
  public void enforce(String clientIp) {
    String key = KEY_PREFIX + clientIp;
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, window);
    }
    if (count != null && count > maxAttempts) {
      audit.record(
          AuditAction.CLAIM_REDEEM_THROTTLED,
          "claim_code",
          null,
          Map.of("ip", clientIp, "count", count));
      throw new ValidationException(ErrorCode.KH_CLM_0429, "error.clm.throttled");
    }
  }
}
