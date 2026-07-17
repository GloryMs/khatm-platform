package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Spec FS-0.6b DoD #1 — a full {@code login → me → logout} cycle works with the {@code
 * KHATM_SESSION} cookie (D1: server-side, Redis-backed), and the session no longer works after
 * logout.
 */
class AuthLoginCycleTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void loginThenMeThenLogout_worksWithSessionCookie_andLogoutInvalidatesIt() throws Exception {
    ResponseEntity<Void> loginResponse =
        rest.postForEntity(
            "/api/auth/login",
            Map.of("username", BOOTSTRAP_ADMIN_USERNAME, "password", BOOTSTRAP_ADMIN_PASSWORD),
            Void.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String sessionCookie = extractCookie(loginResponse, "KHATM_SESSION");
    assertThat(sessionCookie).as("KHATM_SESSION cookie must be set on login").isNotBlank();
    String csrfCookie = extractCookie(loginResponse, "XSRF-TOKEN");
    assertThat(csrfCookie).as("XSRF-TOKEN cookie must be set (CsrfCookieFilter)").isNotBlank();
    String csrfValue = csrfCookie.substring(csrfCookie.indexOf('=') + 1);

    ResponseEntity<String> meResponse =
        rest.exchange("/api/auth/me", HttpMethod.GET, withCookie(sessionCookie), String.class);
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
    assertThat(scopes).contains("admin", "issue", "verify", "consume", "revoke");

    HttpHeaders logoutHeaders = new HttpHeaders();
    logoutHeaders.set(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
    logoutHeaders.set("X-XSRF-TOKEN", csrfValue);
    ResponseEntity<Void> logoutResponse =
        rest.exchange(
            "/api/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), Void.class);
    assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> meAfterLogout =
        rest.exchange("/api/auth/me", HttpMethod.GET, withCookie(sessionCookie), String.class);
    assertThat(meAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private static String extractCookie(ResponseEntity<?> response, String cookieName) {
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    if (setCookies == null) {
      return null;
    }
    for (String setCookie : setCookies) {
      if (setCookie.startsWith(cookieName + "=")) {
        return setCookie.substring(
            0, setCookie.indexOf(';') >= 0 ? setCookie.indexOf(';') : setCookie.length());
      }
    }
    return null;
  }

  private static HttpEntity<Void> withCookie(String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.COOKIE, cookie);
    return new HttpEntity<>(headers);
  }
}
