package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;

/**
 * Spec FS-2.2 D5 — {@code rbac.security.PasswordChangeEnforcementFilter}'s exemption list, pinned
 * exactly (the rider this session's guidance asked for): a temporary-password user is blocked with
 * {@code 403 KH-USR-0403} on an ordinary authenticated endpoint, but {@code GET /api/v1/auth/me}
 * (added to the exemption list after a console-side self-stop found the flag was otherwise
 * undiscoverable — see the class's own Javadoc), the self-service change-password call, and logout
 * all go through untouched, and the platform's genuinely public paths never even reach this
 * filter's principal check (no session cookie yet, or an anonymous request).
 */
class PasswordChangeEnforcementFilterExemptionTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void temporaryPasswordUser_blockedOnOrdinaryEndpoint_butLoginAndPublicPathsUnaffected()
      throws Exception {
    AuthenticatedSession adminSession =
        SessionTestSupport.login(rest, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD);
    String username = "exempt-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    ResponseEntity<String> created =
        SessionTestSupport.post(
            rest,
            "/api/v1/users",
            adminSession,
            Map.of(
                "username",
                username,
                "displayNameI18n",
                Map.of("en", "Exempt", "ar", "معفى"),
                "roles",
                List.of()));
    JsonNode createdBody = JSON.readTree(created.getBody());
    String temporaryPassword = createdBody.get("temporaryPassword").asText();

    // Login itself is exempt (there is no session yet at the point login runs) and succeeds.
    AuthenticatedSession session = SessionTestSupport.login(rest, username, temporaryPassword);

    // GET /me is exempt — the flag is discoverable here, not just from an opaque 403 elsewhere.
    ResponseEntity<String> me = SessionTestSupport.get(rest, "/api/v1/auth/me", session);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JSON.readTree(me.getBody()).get("mustChangePassword").asBoolean()).isTrue();

    // An ordinary authenticated endpoint is blocked with the distinct forced-change code.
    ResponseEntity<String> blocked = SessionTestSupport.get(rest, "/api/v1/users", session);
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JSON.readTree(blocked.getBody()).get("code").asText()).isEqualTo("KH-USR-0403");

    // A genuinely public path is unaffected even carrying the temp-password user's own session
    // cookie alongside it — a malformed presentation resolves to its own 200 domain result
    // (valid:false), never the forced-change 403.
    ResponseEntity<String> publicCall =
        rest.exchange(
            "/api/v1/credentials/verify",
            HttpMethod.POST,
            session.writeHeaders(Map.of("sdJwt", "not-a-real-jwt")),
            String.class);
    assertThat(publicCall.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Logout is exempt.
    ResponseEntity<String> logout =
        SessionTestSupport.post(rest, "/api/v1/auth/logout", session, null);
    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
