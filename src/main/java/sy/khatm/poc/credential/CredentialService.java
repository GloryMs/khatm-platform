package sy.khatm.poc.credential;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.poc.credential.dto.Dtos.*;
import sy.khatm.poc.key.KeyService;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CredentialService {

    private final CredentialRepository credentials;
    private final ConsumptionEventRepository events;
    private final KeyService keys;
    private final StringRedisTemplate redis;

    @Value("${khatm.issuer-did:did:web:khatm.sy:demo}")
    private String issuerDid;

    public CredentialService(CredentialRepository credentials,
                             ConsumptionEventRepository events,
                             KeyService keys,
                             StringRedisTemplate redis) {
        this.credentials = credentials;
        this.events = events;
        this.keys = keys;
        this.redis = redis;
    }

    /* ---------------- ISSUE ---------------- */
    @Transactional
    public IssueResponse issue(IssueRequest req) throws Exception {
        UUID id = UUID.randomUUID();
        int maxUses = req.maxUses() == null ? 1 : req.maxUses();
        int validMinutes = req.validMinutes() == null ? 60 : req.validMinutes();
        String schemaCode = req.schemaCode() == null ? "GenericDocument/v1" : req.schemaCode();

        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(validMinutes));
        int statusIdx = ThreadLocalRandom.current().nextInt(1, 1_000_000);
        String ref = buildRef(schemaCode);

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuerDid)
                .subject(req.holderRef() == null ? "holder-demo" : req.holderRef())
                .claim("ref", ref)
                .claim("vct", schemaCode)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("use", Map.of("max", maxUses))
                .claim("status", Map.of("idx", statusIdx));

        if (req.claims() != null) {
            req.claims().forEach(claims::claim);
        }

        String jwt = keys.sign(claims.build());

        Credential c = new Credential();
        c.setId(id);
        c.setRef(ref);
        c.setSchemaCode(schemaCode);
        c.setHolderRef(req.holderRef());
        c.setSignedJwt(jwt);
        c.setValidFrom(now);
        c.setValidTo(exp);
        c.setMaxUses(maxUses);
        c.setUsesRemaining(maxUses);
        c.setStatusIdx(statusIdx);
        c.setRevoked(false);
        c.setCreatedAt(now);
        credentials.save(c);

        return new IssueResponse(id.toString(), ref, jwt);
    }

    /* ---------------- VERIFY (online: signature + status) ---------------- */
    @Transactional(readOnly = true)
    public VerifyResponse verify(String token) {
        SignedJWT parsed;
        JWTClaimsSet claimsSet;
        try {
            parsed = SignedJWT.parse(token);
            claimsSet = parsed.getJWTClaimsSet();
        } catch (Exception e) {
            return new VerifyResponse(false, "malformed_token", null, null, false);
        }

        if (!keys.verifySignature(parsed)) {
            return new VerifyResponse(false, "bad_signature", null, null, false);
        }

        Map<String, Object> claims = claimsSet.getClaims();

        Date exp = claimsSet.getExpirationTime();
        if (exp != null && exp.toInstant().isBefore(Instant.now())) {
            return new VerifyResponse(false, "expired", claims, null, false);
        }

        String ref = (String) claims.get("ref");
        Optional<Credential> maybe = ref == null ? Optional.empty() : credentials.findByRef(ref);
        if (maybe.isEmpty()) {
            // signature is valid but we don't know this ref (still authentic offline)
            return new VerifyResponse(true, "valid_signature_unknown_ref", claims, null, false);
        }
        Credential c = maybe.get();
        if (c.isRevoked()) {
            return new VerifyResponse(false, "revoked", claims, c.getUsesRemaining(), true);
        }
        return new VerifyResponse(true, "valid", claims, c.getUsesRemaining(), false);
    }

    /* ---------------- CONSUME (atomic — the double-spend guard) ---------------- */
    @Transactional
    public ConsumeResponse consume(ConsumeRequest req) {
        UUID id;
        try {
            id = UUID.fromString(req.id());
        } catch (Exception e) {
            return new ConsumeResponse(false, "bad_id", null);
        }

        // Redis idempotency: repeated calls with the same key return the first outcome.
        String idemKey = req.idempotencyKey();
        if (idemKey != null && !idemKey.isBlank()) {
            String cached = safeRedisGet("consume:idem:" + idemKey);
            if (cached != null) {
                return new ConsumeResponse(cached.startsWith("OK"), "idempotent_replay", null);
            }
        }

        int updated = credentials.consumeOne(id);            // 0 or 1, atomic
        boolean consumed = updated == 1;

        if (consumed) {
            String consumer = req.consumer() == null ? "unknown-consumer" : req.consumer();
            events.save(new ConsumptionEvent(id, consumer, "online"));
        }

        if (idemKey != null && !idemKey.isBlank()) {
            safeRedisSet("consume:idem:" + idemKey, consumed ? "OK" : "NO", Duration.ofHours(1));
        }

        Integer remaining = credentials.findById(id).map(Credential::getUsesRemaining).orElse(null);
        String reason = consumed ? "consumed"
                : (remaining != null && remaining <= 0 ? "already_consumed" : "not_consumable");
        return new ConsumeResponse(consumed, reason, remaining);
    }

    /* ---------------- REVOKE ---------------- */
    @Transactional
    public boolean revoke(UUID id) {
        Optional<Credential> maybe = credentials.findById(id);
        if (maybe.isEmpty()) return false;
        Credential c = maybe.get();
        c.setRevoked(true);
        credentials.save(c);
        return true;
    }

    public Optional<Credential> get(UUID id) {
        return credentials.findById(id);
    }

    /* ---------------- helpers ---------------- */
    private String buildRef(String schemaCode) {
        String prefix = schemaCode.replaceAll("[^A-Za-z]", "");
        prefix = prefix.length() >= 3 ? prefix.substring(0, 3).toUpperCase() : "DOC";
        int n = ThreadLocalRandom.current().nextInt(100000, 999999);
        return prefix + "-" + java.time.Year.now() + "-" + n;
    }

    private String safeRedisGet(String key) {
        try { return redis.opsForValue().get(key); } catch (Exception e) { return null; }
    }

    private void safeRedisSet(String key, String val, Duration ttl) {
        try { redis.opsForValue().set(key, val, ttl); } catch (Exception ignored) {}
    }
}
