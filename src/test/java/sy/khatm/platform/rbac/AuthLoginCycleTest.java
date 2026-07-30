package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;

/**
 * Spec FS-0.6b DoD #1 — a full {@code login → me → logout} cycle works with the {@code
 * KHATM_SESSION} cookie (D1: server-side, Redis-backed), and the session no longer works after
 * logout.
 *
 * <p>Uses {@link SessionTestSupport#login} (not a raw {@code POST /api/v1/auth/login} call) because
 * the bootstrap admin holds {@code platform:admin} (spec FS-2.2 V1's mandatory-2FA set) — by the
 * time this test runs in the shared context, an earlier test may already have enrolled TOTP for
 * this same username via that helper, in which case a raw login here would receive a {@code
 * totpRequired} challenge instead of a session cookie. The helper transparently completes that
 * challenge (or performs first-time enrollment) so this test can focus on the login→me→logout cycle
 * itself.
 */
class AuthLoginCycleTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void loginThenMeThenLogout_worksWithSessionCookie_andLogoutInvalidatesIt() throws Exception {
    AuthenticatedSession session =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String sessionCookie = session.sessionCookie();
    String csrfCookie = session.csrfCookie();
    String csrfValue = session.csrfValue();

    ResponseEntity<String> meResponse =
        rest.exchange("/api/v1/auth/me", HttpMethod.GET, withCookie(sessionCookie), String.class);
    assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode me = JSON.readTree(meResponse.getBody());
    assertThat(me.get("username").asText()).isEqualTo(BOOTSTRAP_ADMIN_USERNAME);
    assertThat(me.get("preferredLang").asText()).isEqualTo("ar");
    assertThat(me.get("displayNameI18n").get("en").asText()).isNotBlank();
    assertThat(me.get("displayNameI18n").get("ar").asText()).isNotBlank();
    List<String> scopes =
        JSON.convertValue(
            me.get("scopes"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class));
    // KH-2.2a (spec FS-2.2 D3): the bootstrap admin holds PLATFORM_ADMIN, now all nine granular
    // scopes — the retired 'admin' scope no longer appears anywhere.
    assertThat(scopes)
        .containsExactlyInAnyOrder(
            "issue",
            "verify",
            "consume",
            "revoke",
            "schema:manage",
            "consumer:manage",
            "key:manage",
            "tenant:admin",
            "platform:admin");

    HttpHeaders logoutHeaders = new HttpHeaders();
    logoutHeaders.set(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
    logoutHeaders.set("X-XSRF-TOKEN", csrfValue);
    ResponseEntity<Void> logoutResponse =
        rest.exchange(
            "/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), Void.class);
    assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> meAfterLogout =
        rest.exchange("/api/v1/auth/me", HttpMethod.GET, withCookie(sessionCookie), String.class);
    assertThat(meAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private static HttpEntity<Void> withCookie(String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.COOKIE, cookie);
    return new HttpEntity<>(headers);
  }
}
