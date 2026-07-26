package sy.khatm.platform.tenant.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.rbac.RbacHttpTestSupport;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.AppUser;
import sy.khatm.platform.rbac.domain.Role;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * Spec FS-1.3 DoD #3 / FS-2.1 D8 — {@code GET /sl/{tenantSlug}/{listCode}} at the HTTP level:
 * public with zero credentials, returns a JWS a client can validate the claims of, {@code
 * ETag}/{@code Cache-Control} are present, and a matching {@code If-None-Match} gets a bodyless
 * 304.
 *
 * <p>Relocated from {@code status.web} to {@code tenant.web} alongside its controller (spec FS-2.1
 * D8) — unchanged otherwise, it only ever exercised the endpoint over real HTTP.
 */
class StatusListControllerHttpTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "status-list-http-test-password";

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AppUserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void getStatusList_withNoCredentialsAtAll_returns200WithSignedArtifact() throws Exception {
    // Issuing (default list) + revoking guarantees a list exists and is stale, exercising the
    // controller's lazy-publish fallback exactly like a fresh deployment's first request would.
    String listCode = issueAndRevoke();

    ResponseEntity<String> response =
        rest.getForEntity("/sl/khatm-default/" + listCode, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).hasToString("application/jose");
    assertThat(response.getHeaders().getETag()).isNotBlank();
    assertThat(response.getHeaders().getCacheControl()).contains("max-age=60");

    JWTClaimsSet claims = SignedJWT.parse(response.getBody()).getJWTClaimsSet();
    assertThat(claims.getStringClaim("list")).isEqualTo(listCode);
    assertThat(claims.getLongClaim("ver")).isPositive();
  }

  @Test
  void getStatusList_withMatchingIfNoneMatch_returns304WithNoBody() throws Exception {
    String listCode = issueAndRevoke();

    ResponseEntity<String> first = rest.getForEntity("/sl/khatm-default/" + listCode, String.class);
    String eTag = first.getHeaders().getETag();

    ResponseEntity<String> second =
        rest.exchange(
            RequestEntity.get("/sl/khatm-default/" + listCode)
                .header(HttpHeaders.IF_NONE_MATCH, eTag)
                .build(),
            String.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(second.getBody()).isNullOrEmpty();
  }

  @Test
  void getStatusList_unknownListCode_returns404() {
    ResponseEntity<String> response =
        rest.getForEntity("/sl/khatm-default/no-such-list-" + UUID.randomUUID(), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void getStatusList_wrongTenantSlug_returns404() throws Exception {
    String listCode = issueAndRevoke();

    ResponseEntity<String> response =
        rest.getForEntity("/sl/not-a-real-tenant/" + listCode, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /**
   * Issues a credential via a TENANT API key, then revokes it via a fresh console session (revoke
   * is session-only, {@code ACTOR_USER} — spec §3) — leaving behind a stale status list to serve.
   * Returns the list's code.
   */
  private String issueAndRevoke() throws Exception {
    String issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    JsonNode issued = issue(issuerKey);
    String credentialId = issued.get("id").asText();

    revokeViaSession(credentialId);

    UUID statusListId =
        jdbc.queryForObject(
            "SELECT status_list_id FROM credential WHERE id = ?",
            UUID.class,
            UUID.fromString(credentialId));
    return jdbc.queryForObject(
        "SELECT list_code FROM status_list WHERE id = ?", String.class, statusListId);
  }

  private JsonNode issue(String rawApiKey) throws Exception {
    Map<String, Object> issueRequest =
        Map.of(
            "schemaCode",
            "StatusListHttpProbe/v1",
            "holderRef",
            "holder-status-http-" + UUID.randomUUID(),
            "claims",
            Map.of("field", "value"));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/credentials/issue",
            HttpMethod.POST,
            new HttpEntity<>(issueRequest, headers),
            String.class);
    return JSON.readTree(response.getBody());
  }

  /**
   * revoke requires a console session (session-only, {@code ACTOR_USER} — API keys are always 403).
   * {@code rbac.SessionTestSupport} is package-private to {@code rbac}, so this test (living in
   * {@code tenant.web}) creates its own {@code ISSUER_OPERATOR} user and does the minimal
   * login-cookie dance itself, mirroring {@code rbac.ClaimCodeMintScopeGateTest}'s user-creation
   * pattern.
   */
  private void revokeViaSession(String credentialId) {
    String username = createOperatorUser();
    ResponseEntity<Void> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", username, "password", PASSWORD), Void.class);
    String sessionCookie = extractCookie(loginResponse, "KHATM_SESSION");
    String csrfCookie = extractCookie(loginResponse, "XSRF-TOKEN");
    String csrfValue = csrfCookie.substring(csrfCookie.indexOf('=') + 1);

    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
    headers.set("X-XSRF-TOKEN", csrfValue);
    rest.exchange(
        "/api/v1/credentials/" + credentialId + "/revoke",
        HttpMethod.POST,
        new HttpEntity<>(headers),
        String.class);
  }

  private String createOperatorUser() {
    String username = "status-list-http-operator-" + UUID.randomUUID();
    // See rbac.ScopeGateTest's identical helper for why TransactionTemplate (not a plain method,
    // not a self-invoked @Transactional method) is required here.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              AppUser user = new AppUser();
              user.setId(Uuidv7.generate());
              user.setTenantId(TenantContext.current());
              user.setUsername(username);
              user.setPasswordHash(passwordEncoder.encode(PASSWORD));
              user.setDisplayNameI18n(new LocalizedText(username, username));
              user.setPreferredLang("en");
              user.setStatus("ACTIVE");
              user.setCreatedAt(Instant.now());
              users.save(user);
              Role role =
                  roles
                      .findByTenantIdAndCode(TenantContext.current(), "ISSUER_OPERATOR")
                      .orElseThrow();
              roles.assignRole(user.getId(), role.getId());
            });
    return username;
  }

  private static String extractCookie(ResponseEntity<?> response, String cookieName) {
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
}
