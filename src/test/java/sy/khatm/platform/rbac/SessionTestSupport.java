package sy.khatm.platform.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.support.TotpEnrollmentCache;
import sy.khatm.platform.support.TotpTestCodes;

/**
 * Shared helper for KH-0.6b's session-cookie HTTP tests: log in and capture the {@code
 * KHATM_SESSION} + {@code XSRF-TOKEN} cookies, then attach both to subsequent state-changing calls
 * exactly the way a real browser console would (spec FS-0.6b §3).
 *
 * <p><b>KH-2.2c — transparent TOTP bootstrap:</b> every mandatory-2FA scope (spec FS-2.2 V1) is
 * granted to at least one of the fixed seeded roles every {@code tenant:admin}/{@code
 * platform:admin} HTTP test fixture logs in as, so {@code rbac.security
 * .TotpEnrollmentEnforcementFilter} would wall off virtually every existing scope-gate test's
 * session immediately after login if this helper did nothing about it. {@link #login} therefore
 * transparently enrolls + confirms TOTP for a username the first time it logs in (via the real
 * {@code POST /users/me/totp/enroll}/{@code /confirm} endpoints — no direct database/encryption
 * bypass), remembering the Base32 secret in the cross-package {@link TotpEnrollmentCache} for the
 * lifetime of the test JVM (shared with {@code db.CrossTenantIsolationTest}, which extends {@code
 * RbacHttpTestSupport} too and therefore reuses the same cached context/database — see that cache's
 * own Javadoc for why a package-private cache here would not be enough); every later login for that
 * same username (the shared-context suite logs the same fixed {@code BOOTSTRAP_ADMIN_USERNAME} in
 * repeatedly across many test classes) then completes the resulting TOTP challenge automatically
 * using {@link TotpTestCodes}, so every existing call site of this helper needs zero changes. Tests
 * that specifically exercise the TOTP feature itself (enrollment, the login challenge, the
 * mandatory-enrollment wall) call the real endpoints directly instead of this helper, the same way
 * any other feature-specific test bypasses a generic helper when it needs to observe the exact
 * mechanism under test.
 */
final class SessionTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> ENROLLED_SECRETS = TotpEnrollmentCache.SECRETS;

  private SessionTestSupport() {}

  /**
   * Log in against the caller's ambient default tenant and return the established session, ready to
   * attach to further requests.
   *
   * @throws AssertionError if login did not succeed with 200
   */
  static AuthenticatedSession login(TestRestTemplate rest, String username, String password) {
    return login(rest, username, password, null);
  }

  /**
   * Log in, optionally against an explicitly named tenant (spec FS-2.2 — multi-tenant console
   * login), and return the established session, ready to attach to further requests.
   *
   * @param tenantSlug the tenant to authenticate against, or {@code null} for the caller's ambient
   *     default tenant (identical wire shape to the two-arg overload — {@code tenantSlug} is
   *     omitted from the body entirely, not sent as an explicit {@code null})
   * @throws AssertionError if login did not succeed with 200
   */
  static AuthenticatedSession login(
      TestRestTemplate rest, String username, String password, String tenantSlug) {
    Map<String, Object> body = new HashMap<>();
    body.put("username", username);
    body.put("password", password);
    if (tenantSlug != null) {
      body.put("tenantSlug", tenantSlug);
    }
    ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login", body, String.class);
    if (response.getStatusCode() != HttpStatus.OK) {
      throw new AssertionError("Login failed with status " + response.getStatusCode());
    }

    if (isTotpChallenge(response)) {
      String challengeId = readChallengeId(response);
      String secret = ENROLLED_SECRETS.get(username);
      if (secret == null) {
        throw new AssertionError(
            "Login issued a TOTP challenge for '"
                + username
                + "' but no secret was ever enrolled via this helper — a prior test must have"
                + " enrolled TOTP for this user some other way.");
      }
      Map<String, Object> completeBody =
          Map.of("challengeId", challengeId, "code", TotpTestCodes.currentCode(secret));
      ResponseEntity<Void> completed =
          rest.postForEntity("/api/v1/auth/totp", completeBody, Void.class);
      if (completed.getStatusCode() != HttpStatus.OK) {
        throw new AssertionError(
            "TOTP challenge completion failed with " + completed.getStatusCode());
      }
      return sessionFrom(completed);
    }

    AuthenticatedSession session = sessionFrom(response);
    if (!ENROLLED_SECRETS.containsKey(username)) {
      // Best-effort: a temporary-password user is blocked from every endpoint except the
      // password-change one by rbac.security.PasswordChangeEnforcementFilter, enroll included — in
      // that case there is nothing to do here yet; a later login for the same username (after the
      // password is changed) tries again and succeeds.
      tryEnrollAndConfirmTotp(rest, session)
          .ifPresent(secret -> ENROLLED_SECRETS.put(username, secret));
    }
    return session;
  }

  private static boolean isTotpChallenge(ResponseEntity<String> response) {
    String body = response.getBody();
    if (body == null || body.isBlank()) {
      return false;
    }
    JsonNode node = readTree(body);
    return node.path("totpRequired").asBoolean(false);
  }

  private static String readChallengeId(ResponseEntity<String> response) {
    return readTree(response.getBody()).path("challengeId").asText();
  }

  private static JsonNode readTree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse login response body: " + body, e);
    }
  }

  /**
   * Best-effort enroll + confirm TOTP for the just-logged-in session via the real endpoints, so
   * this username's mandatory-2FA scopes (if any) never wall off the rest of this test — empty if
   * enrollment could not proceed right now (e.g. a temporary-password user still blocked by {@code
   * PasswordChangeEnforcementFilter}), never thrown, since most callers of {@link #login} are
   * testing something else entirely and must not fail because of this bookkeeping.
   */
  private static Optional<String> tryEnrollAndConfirmTotp(
      TestRestTemplate rest, AuthenticatedSession session) {
    ResponseEntity<String> enrollResponse =
        rest.exchange(
            "/api/v1/users/me/totp/enroll", HttpMethod.POST, session.writeHeaders(), String.class);
    if (enrollResponse.getStatusCode() != HttpStatus.OK) {
      return Optional.empty();
    }
    String secret = readTree(enrollResponse.getBody()).path("secretBase32").asText();

    HttpHeaders confirmHeaders = new HttpHeaders();
    confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
    confirmHeaders.set(HttpHeaders.COOKIE, session.sessionCookie() + "; " + session.csrfCookie());
    confirmHeaders.set("X-XSRF-TOKEN", session.csrfValue());
    HttpEntity<String> confirmRequest =
        new HttpEntity<>(
            "{\"code\":\"" + TotpTestCodes.currentCode(secret) + "\"}", confirmHeaders);
    ResponseEntity<String> confirmResponse =
        rest.exchange(
            "/api/v1/users/me/totp/confirm", HttpMethod.POST, confirmRequest, String.class);
    if (confirmResponse.getStatusCode() != HttpStatus.OK) {
      return Optional.empty();
    }
    return Optional.of(secret);
  }

  private static AuthenticatedSession sessionFrom(ResponseEntity<?> response) {
    String sessionCookie = extractCookie(response, "KHATM_SESSION");
    String csrfCookie = extractCookie(response, "XSRF-TOKEN");
    if (sessionCookie == null || csrfCookie == null) {
      throw new AssertionError("Response missing session or CSRF cookie");
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
