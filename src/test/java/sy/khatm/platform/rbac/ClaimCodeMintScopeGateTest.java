package sy.khatm.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.rbac.SessionTestSupport.AuthenticatedSession;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;
import sy.khatm.platform.rbac.domain.AppUser;
import sy.khatm.platform.rbac.domain.CreatedApiKey;
import sy.khatm.platform.rbac.domain.Role;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.RoleRepository;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;

/**
 * KH-1.2.2 — {@code POST /api/v1/credentials/{id}/claim-code} reuses {@code /issue}'s exact scope
 * rule (spec's "session or TENANT API key" requirement, {@code
 * ScopeGuard#requireScopeNotConsumingPartyKey}): no session/key at all is 401; a valid session or
 * key missing the {@code issue} scope, or a {@code CONSUMING_PARTY} key regardless of scope, is
 * 403; a TENANT API key or an {@code ISSUER_OPERATOR} session with {@code issue} works.
 */
class ClaimCodeMintScopeGateTest extends RbacHttpTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "claim-code-mint-gate-password";

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private AppUserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void mintClaimCode_withNoSessionOrKey_returns401() throws Exception {
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/credentials/" + UUID.randomUUID() + "/claim-code",
            Map.of("sdJwt", "irrelevant"),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0401");
  }

  @Test
  void mintClaimCode_withConsumingPartyKey_returns403() throws Exception {
    CreatedApiKey consumerKey =
        apiKeyService.create(ApiKeyOwnerType.CONSUMING_PARTY, null, Set.of("issue"));

    ResponseEntity<String> response =
        mintWithApiKey(consumerKey.rawKey(), UUID.randomUUID().toString(), "irrelevant");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void mintClaimCode_withTenantKeyMissingIssueScope_returns403() throws Exception {
    CreatedApiKey noScopeKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of());

    ResponseEntity<String> response =
        mintWithApiKey(noScopeKey.rawKey(), UUID.randomUUID().toString(), "irrelevant");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("KH-RBC-0403");
  }

  @Test
  void mintClaimCode_withTenantKeyAndIssueScope_works() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    IssuedCredential issued = issueCredential(issuerKey.rawKey());

    ResponseEntity<String> response =
        mintWithApiKey(issuerKey.rawKey(), issued.id(), issued.sdJwt());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.get("code").asText()).isNotBlank();
    assertThat(body.get("expiresAt").asText()).isNotBlank();
  }

  @Test
  void mintClaimCode_withIssuerOperatorSession_works() throws Exception {
    CreatedApiKey issuerKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue"));
    IssuedCredential issued = issueCredential(issuerKey.rawKey());

    String username = createUser("mint-operator-" + UUID.randomUUID(), "ISSUER_OPERATOR");
    AuthenticatedSession session = SessionTestSupport.login(rest, username, PASSWORD);

    ResponseEntity<String> response =
        SessionTestSupport.post(
            rest,
            "/api/v1/credentials/" + issued.id() + "/claim-code",
            session,
            Map.of("sdJwt", issued.sdJwt()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private IssuedCredential issueCredential(String rawApiKey) throws Exception {
    Map<String, Object> issueRequest =
        Map.of(
            "schemaCode",
            "MintGateProbe/v1",
            "holderRef",
            "holder-mint-gate-" + UUID.randomUUID(),
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
    JsonNode body = JSON.readTree(response.getBody());
    return new IssuedCredential(body.get("id").asText(), body.get("sdJwt").asText());
  }

  private ResponseEntity<String> mintWithApiKey(
      String rawApiKey, String credentialId, String sdJwt) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey);
    return rest.exchange(
        "/api/v1/credentials/" + credentialId + "/claim-code",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("sdJwt", sdJwt), headers),
        String.class);
  }

  private String createUser(String username, String roleCode) {
    // See ScopeGateTest's identical helper for why TransactionTemplate (not a plain method, not a
    // self-invoked @Transactional method) is required here.
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
                  roles.findByTenantIdAndCode(TenantContext.current(), roleCode).orElseThrow();
              roles.assignRole(user.getId(), role.getId());
            });
    return username;
  }

  private record IssuedCredential(String id, String sdJwt) {}
}
