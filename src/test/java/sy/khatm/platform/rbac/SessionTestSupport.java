package sy.khatm.platform.rbac;

import java.util.List;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Shared helper for KH-0.6b's session-cookie HTTP tests: log in and capture the {@code
 * KHATM_SESSION} + {@code XSRF-TOKEN} cookies, then attach both to subsequent state-changing calls
 * exactly the way a real browser console would (spec FS-0.6b §3).
 */
final class SessionTestSupport {

  private SessionTestSupport() {}

  /**
   * Log in and return the established session, ready to attach to further requests.
   *
   * @throws AssertionError if login did not succeed with 200
   */
  static AuthenticatedSession login(TestRestTemplate rest, String username, String password) {
    ResponseEntity<Void> response =
        rest.postForEntity(
            "/api/auth/login", Map.of("username", username, "password", password), Void.class);
    if (response.getStatusCode() != HttpStatus.OK) {
      throw new AssertionError("Login failed with status " + response.getStatusCode());
    }
    String sessionCookie = extractCookie(response, "KHATM_SESSION");
    String csrfCookie = extractCookie(response, "XSRF-TOKEN");
    if (sessionCookie == null || csrfCookie == null) {
      throw new AssertionError("Login response missing session or CSRF cookie");
    }
    String csrfValue = csrfCookie.substring(csrfCookie.indexOf('=') + 1);
    return new AuthenticatedSession(sessionCookie, csrfCookie, csrfValue);
  }

  static String extractCookie(ResponseEntity<?> response, String cookieName) {
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    if (setCookies == null) {
      return null;
    }
    for (String setCookie : setCookies) {
      if (setCookie.startsWith(cookieName + "=")) {
        int semicolon = setCookie.indexOf(';');
        return semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
      }
    }
    return null;
  }

  /**
   * A logged-in session's cookies, ready to attach to GET (session only) or state-changing (session
   * + CSRF) requests.
   */
  record AuthenticatedSession(String sessionCookie, String csrfCookie, String csrfValue) {

    /** Headers for a GET/read-only request — session cookie only, no CSRF needed. */
    HttpEntity<Void> readHeaders() {
      HttpHeaders headers = new HttpHeaders();
      headers.set(HttpHeaders.COOKIE, sessionCookie);
      return new HttpEntity<>(headers);
    }

    /**
     * Headers + body for a state-changing (POST/PUT/DELETE) request — session cookie + CSRF token.
     */
    <T> HttpEntity<T> writeHeaders(T body) {
      HttpHeaders headers = new HttpHeaders();
      headers.set(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
      headers.set("X-XSRF-TOKEN", csrfValue);
      return new HttpEntity<>(body, headers);
    }

    HttpEntity<Void> writeHeaders() {
      return writeHeaders(null);
    }
  }

  static ResponseEntity<String> get(
      TestRestTemplate rest, String url, AuthenticatedSession session) {
    return rest.exchange(url, HttpMethod.GET, session.readHeaders(), String.class);
  }

  static <T> ResponseEntity<String> post(
      TestRestTemplate rest, String url, AuthenticatedSession session, T body) {
    return rest.exchange(url, HttpMethod.POST, session.writeHeaders(body), String.class);
  }
}
