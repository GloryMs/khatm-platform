package sy.khatm.platform.key.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;

/**
 * {@link KeyProvider} backed by HashiCorp Vault's Transit secrets engine (spec FS-2.3 D5) —
 * self-hosted/on-prem KMS, the target market's first choice over a foreign cloud KMS (veto V1).
 *
 * <p>Private key material never leaves Vault: keys are created {@code exportable=false} and every
 * signature is produced by Vault's own {@code transit/sign} endpoint — this class only ever holds
 * an opaque {@code providerRef} (the transit key name) and, transiently, the bytes Vault returns.
 * No PKCS#12-style local material handling exists here at all (contrast {@link SoftKeyProvider}).
 *
 * <p>Talks to Vault over its plain HTTP API via Spring's {@link RestClient} — no Vault client SDK
 * dependency, so the app's classpath/attack surface is unaffected by this provider's presence. The
 * app token this class authenticates with must be scoped to a least-privilege policy covering only
 * {@code transit/keys/*} ({@code create+update+read} — {@code update} is required too, established
 * empirically on staging 2026-08-15: Vault's ACL layer evaluates every write to {@code
 * transit/keys/:name} as {@code update} regardless of whether the key already exists, since that
 * path registers no existence check), {@code transit/sign/*}, and {@code transit/verify/*} — never
 * an admin/root token (see {@code docs/deploy-staging.md}'s Vault hardening section).
 *
 * <p><b>Fail-closed, never a silent SOFT fallback (spec FS-2.3 D5/D6):</b> a connectivity failure
 * or a Vault-side error at {@link #generate}/{@link #sign} time throws {@link IntegrityException}
 * {@code KH-KEY-0503} — a distinct, alarm-friendly code an operator or monitor can page on, logged
 * at ERROR. Silently falling back to SOFT for a Vault-configured tenant would be a key-security
 * downgrade the platform must never perform on its own initiative.
 *
 * <p><b>JOSE (raw) vs. DER signature marshaling:</b> the request asks Vault for {@code
 * marshaling_algorithm=jws} — Vault's own name (confirmed against a real Vault 1.17's {@code vault
 * path-help transit/sign/:name}; its own transit code names the parameter's JOSE-shaped value
 * {@code "jws"}, not {@code "jose"} — a real, empirically-caught mistake during this session's own
 * live-Vault test run, not a documentation guess) for returning the fixed-length {@code r || s}
 * concatenation JWS's ES256 requires, instead of its default ASN.1 DER encoding. Vault also
 * switches the response's own signature encoding to URL-safe base64 (no padding) under {@code jws}
 * marshaling — {@link #sign} decodes with {@link Base64#getUrlDecoder()} accordingly, never the
 * standard alphabet. {@link #normalizeToRawJoseSignature} defensively re-checks the byte length
 * regardless: any response that is not already exactly {@link ECDSA#getSignatureByteArrayLength}
 * bytes long is treated as DER and transcoded via {@link ECDSA#transcodeSignatureToConcat} (the
 * same Nimbus utility {@code ECDSASigner}/{@code ECDSAVerifier} use internally for JCA interop) —
 * so a Vault version/policy that ignores the marshaling parameter still produces a correct
 * signature rather than a silently-broken one. {@code key.EcdsaSignatureMarshalingTest} proves both
 * paths with a real DER test vector.
 *
 * <p>Module-private. Registered only when {@code khatm.keys.vault.enabled=true} — absent from the
 * {@link KeyLifecycleService} provider map entirely on a deployment that never configured Vault, so
 * a rotate request naming {@code VAULT} there fails closed with {@code KH-KEY-0400} (unknown
 * provider), never a silent no-op.
 */
@Component("VAULT")
@ConditionalOnProperty(name = "khatm.keys.vault.enabled", havingValue = "true")
class VaultTransitProvider implements KeyProvider {

  private static final Logger log = LoggerFactory.getLogger(VaultTransitProvider.class);
  private static final String KEY_TYPE = "ecdsa-p256";
  private static final String HASH_ALGORITHM = "sha2-256";
  private static final String MARSHALING_ALGORITHM = "jws";

  private final RestClient restClient;
  private final String keyNamePrefix;

  VaultTransitProvider(
      @Value("${khatm.keys.vault.address}") String address,
      @Value("${khatm.keys.vault.token}") String token,
      @Value("${khatm.keys.vault.key-name-prefix:khatm}") String keyNamePrefix,
      @Value("${khatm.keys.vault.connect-timeout:PT3S}") Duration connectTimeout,
      @Value("${khatm.keys.vault.read-timeout:PT5S}") Duration readTimeout) {
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "khatm.keys.vault.enabled=true but khatm.keys.vault.token is blank — refusing to start"
              + " a KeyProvider with no way to authenticate to Vault.");
    }
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
    requestFactory.setReadTimeout((int) readTimeout.toMillis());
    this.restClient =
        RestClient.builder()
            .baseUrl(address)
            .requestFactory(requestFactory)
            .defaultHeader("X-Vault-Token", token)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .build();
    this.keyNamePrefix = keyNamePrefix;
  }

  @Override
  public GeneratedKeyMaterial generate(String kid) {
    String transitKeyName = transitKeyName(kid);
    try {
      restClient
          .post()
          .uri("/v1/transit/keys/{name}", transitKeyName)
          .body(
              Map.of(
                  "type", KEY_TYPE,
                  "exportable", false,
                  "allow_plaintext_backup", false,
                  "derived", false))
          .retrieve()
          .toBodilessEntity();
      ECPublicKey publicKey = readPublicKey(transitKeyName);
      ECKey jwk = new ECKey.Builder(Curve.P_256, publicKey).keyID(kid).build();
      return new GeneratedKeyMaterial(kid, jwk.toPublicJWK().toJSONString(), transitKeyName);
    } catch (RestClientException e) {
      throw unavailable("create", transitKeyName, e);
    }
  }

  @Override
  public SignResult sign(String providerRef, String kid, JWTClaimsSet claims) throws JOSEException {
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(kid).build();
    String signingInput = header.toBase64URL() + "." + claims.toPayload().toBase64URL();
    byte[] signingInputBytes = signingInput.getBytes(StandardCharsets.US_ASCII);

    JsonNode response;
    try {
      response =
          restClient
              .post()
              .uri("/v1/transit/sign/{name}", providerRef)
              .body(
                  Map.of(
                      "input", Base64.getEncoder().encodeToString(signingInputBytes),
                      "hash_algorithm", HASH_ALGORITHM,
                      "marshaling_algorithm", MARSHALING_ALGORITHM))
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientException e) {
      throw unavailable("sign", providerRef, e);
    }
    JsonNode data = requireNode(response, "data", providerRef);
    String vaultSignature = requireField(data, "signature", providerRef);
    // Vault's own wire format is "vault:v<version>:<signature>" — only the last segment is the
    // signature itself; the version prefix is Vault's key-versioning metadata, irrelevant here
    // since KeyLifecycleService already pins one issuer_key row to one providerRef/version pair.
    // marshaling_algorithm=jws (see this class's own Javadoc) also switches Vault's OWN encoding
    // of that segment to URL-safe base64 (no padding) — the standard alphabet would silently
    // mis-decode a signature whose raw bytes happen to need a '+'/'/' character.
    String[] parts = vaultSignature.split(":");
    byte[] rawSignatureCandidate = Base64.getUrlDecoder().decode(parts[parts.length - 1]);
    byte[] rawSignature = normalizeToRawJoseSignature(rawSignatureCandidate);

    String jws = signingInput + "." + Base64URL.encode(rawSignature);
    return new SignResult(kid, JWSAlgorithm.ES256.getName(), jws);
  }

  @Override
  public Optional<PublicKeyHandle> publicKey(String providerRef, String kid) {
    try {
      return Optional.of(
          new PublicKeyHandle(kid, JWSAlgorithm.ES256.getName(), readPublicKey(providerRef)));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw unavailable("read", providerRef, e);
    }
  }

  private ECPublicKey readPublicKey(String transitKeyName) {
    JsonNode response =
        restClient
            .get()
            .uri("/v1/transit/keys/{name}", transitKeyName)
            .retrieve()
            .body(JsonNode.class);
    JsonNode data = requireNode(response, "data", transitKeyName);
    int latestVersion = requireNode(data, "latest_version", transitKeyName).asInt();
    JsonNode versionNode =
        requireNode(data, "keys", transitKeyName).get(String.valueOf(latestVersion));
    if (versionNode == null) {
      throw new IllegalStateException(
          "Vault transit key '"
              + transitKeyName
              + "' has no key material at its own latest_version");
    }
    String pem = requireField(versionNode, "public_key", transitKeyName);
    return parseEcPublicKeyPem(pem);
  }

  /**
   * Defensively normalizes a Vault transit signature to the fixed-length {@code r || s} (JOSE)
   * format ES256 requires — see this class's own Javadoc for why this check exists even though the
   * request already asked for {@code marshaling_algorithm=jose}.
   *
   * <p>Package-private (not {@code private}) specifically so {@code
   * key.domain.EcdsaSignatureMarshalingTest} can exercise it directly with a real DER test vector —
   * with no Vault container needed for what is, at its core, a pure encoding question.
   */
  static byte[] normalizeToRawJoseSignature(byte[] signature) throws JOSEException {
    int expectedRawLength = ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256);
    if (signature.length == expectedRawLength) {
      return signature;
    }
    return ECDSA.transcodeSignatureToConcat(signature, expectedRawLength);
  }

  private static ECPublicKey parseEcPublicKeyPem(String pem) {
    String base64 =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      KeyFactory keyFactory = KeyFactory.getInstance("EC");
      PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(der));
      return (ECPublicKey) publicKey;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Vault returned an unparseable EC public key PEM", e);
    }
  }

  private String transitKeyName(String kid) {
    // "include tenant slug for operability" (session brief) — kid already embeds it (format
    // {tenant-slug}:key-{seq}); ':' is not a safe Vault transit key-name character, so it is
    // replaced rather than percent-encoded, keeping the name readable in Vault's own UI/CLI.
    return keyNamePrefix + "-" + kid.replace(':', '-');
  }

  private static String requireField(JsonNode node, String field, String context) {
    JsonNode value = requireNode(node, field, context);
    if (!value.isTextual()) {
      throw new IllegalStateException(
          "Vault response for '" + context + "' field '" + field + "' was not a string");
    }
    return value.asText();
  }

  private static JsonNode requireNode(JsonNode node, String field, String context) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalStateException(
          "Vault response for '" + context + "' is missing expected field '" + field + "'");
    }
    return value;
  }

  private IntegrityException unavailable(
      String operation, String transitKeyName, RestClientException cause) {
    // Alarm-friendly (SEC §9-compliant: ref/name only, never key material, never a JWT/claims
    // value) — this is exactly the log line an operator's Vault-down runbook step should grep for.
    log.error(
        "Vault transit {} failed — provider unreachable or misconfigured, transitKeyName={}",
        operation,
        transitKeyName,
        cause);
    return new IntegrityException(ErrorCode.KH_KEY_0503, "key.provider-unavailable");
  }
}
