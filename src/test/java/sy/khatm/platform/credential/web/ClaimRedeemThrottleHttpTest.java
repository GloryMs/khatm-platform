package sy.khatm.platform.credential.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.rbac.RbacHttpTestSupport;

/**
 * Spec FS-1.2.1 DoD 5 — the per-IP fixed-window throttle: within the (test-configured, {@link
 * RbacHttpTestSupport} — 5 attempts / 2s) window, attempts up to the budget behave normally; the
 * next one gets {@code 429 KH-CLM-0429} and a single {@code CLAIM_REDEEM_THROTTLED} audit row; once
 * the window elapses, the path resumes with no administrative action — the same recovery shape as
 * {@code rbac.AuthLockoutTest}'s login lockout.
 *
 * <p>Relies on {@link RbacHttpTestSupport#resetClaimRedeemThrottleCounter} to start each test with
 * a clean per-IP counter — every subclass here shares one loopback address, so without that reset
 * this class's budget would be at the mercy of whatever any sibling class's tests did first.
 */
class ClaimRedeemThrottleHttpTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private JdbcTemplate jdbc;

  @Test
  void redeem_sixthAttemptWithinWindow_isThrottled_thenRecoversAfterWindowElapses()
      throws Exception {
    for (int attempt = 1; attempt <= 5; attempt++) {
      ResponseEntity<String> response = redeem("throttle-probe-invalid-code-" + attempt);
      assertThat(response.getStatusCode())
          .as("attempt %d is within budget, rejected only as an invalid code", attempt)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }

    ResponseEntity<String> throttled = redeem("throttle-probe-invalid-code-6");
    assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    JsonNode body = JSON.readTree(throttled.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-CLM-0429");
    assertThat(body.get("messageKey").asText()).isEqualTo("error.clm.throttled");

    Integer throttledAuditCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'CLAIM_REDEEM_THROTTLED'"
                + " AND detail->>'count' = '6'",
            Integer.class);
    assertThat(throttledAuditCount).isEqualTo(1);

    Thread.sleep(2500);
    ResponseEntity<String> recovered = redeem("throttle-probe-invalid-code-after-window");
    assertThat(recovered.getStatusCode())
        .as("after the window elapses, the path resumes with no administrative action")
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<String> redeem(String code) {
    return rest.postForEntity("/api/v1/claims/redeem", Map.of("code", code), String.class);
  }
}
