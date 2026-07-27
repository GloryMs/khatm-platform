package sy.khatm.platform.credential.worker;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.credential.persistence.ClaimCodeRepository;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Task step 7d — the {@code ClaimCodeExpiryWorker} sweep zeroes {@code disclosures_enc} on exactly
 * the expired, unclaimed, still-populated claim codes; leaves unexpired and already-claimed codes
 * untouched; and writes a {@code CLAIM_CODES_EXPIRED} audit row carrying the count.
 *
 * <p>Extends {@link IntegrationTestSupport} (reuses its cached Postgres context — no new boot, no
 * Redis needed: the sweep is a pure DB job). The worker is constructed directly (its bean is
 * worker-role-gated, absent in this {@code test}-profile context) and the test method is
 * {@code @Transactional} so the {@code @Modifying} sweep query has a transaction and every insert
 * (including the audit row) rolls back — keeping the append-only {@code audit_log} clean.
 */
class ClaimCodeExpirySweepTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private ClaimCodeRepository claimCodes;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private AuditService auditService;
  @Autowired private SystemAccessExecutor systemAccess;

  private ClaimCodeExpiryWorker worker;

  @BeforeEach
  void constructWorker() {
    worker = new ClaimCodeExpiryWorker(claimCodes, auditService, systemAccess);
  }

  @Test
  @Transactional
  void sweep_zeroesOnlyExpiredUnclaimedCodes_writesAuditRow_decryptNowImpossible() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "Sweep/v1",
                "holder-sweep-" + UUID.randomUUID(),
                1,
                60,
                Map.of("result", "NO_RECORD"),
                List.of()));
    UUID credentialId = UUID.fromString(issued.id());
    UUID tenant = TenantContext.current();
    // Flush the just-issued credential (and its FK targets) so the raw JDBC inserts below can see
    // them — issue()'s JPA saves are not otherwise flushed before a JdbcTemplate write.
    entityManager.flush();
    // A populated disclosures_enc — its exact bytes don't matter to the sweep (it only NULLs the
    // column, never decrypts); a non-null placeholder is enough to prove "before: set, after:
    // null".
    byte[] enc = sha256("dummy-disclosure-" + UUID.randomUUID());

    // Row 1: expired, unclaimed, populated  -> SHOULD be zeroed.
    UUID expired =
        insertClaimCode(credentialId, tenant, enc, Instant.now().minus(1, ChronoUnit.HOURS), null);
    // Row 2: unexpired, populated           -> untouched.
    UUID unexpired =
        insertClaimCode(credentialId, tenant, enc, Instant.now().plus(1, ChronoUnit.HOURS), null);
    // Row 3: expired but already claimed    -> untouched (claimed_at IS NULL filter excludes it).
    UUID claimed =
        insertClaimCode(
            credentialId,
            tenant,
            enc,
            Instant.now().minus(1, ChronoUnit.HOURS),
            Instant.now().minus(30, ChronoUnit.MINUTES));

    // audit_log is append-only (CLAUDE.md), so this shared-context suite's other, genuinely
    // non-transactional tests (e.g. KH-1.2.1's redeem-vs-sweep concurrency race, which commits for
    // real since it needs cross-connection visibility) can leave real CLAIM_CODES_EXPIRED rows
    // behind that no @Transactional rollback ever removes — a before/after delta is the only
    // count assertion that's robust to that, an absolute count is not.
    Integer auditCountBefore = countClaimCodesExpiredAuditRows();
    int zeroed = worker.sweep();

    assertThat(zeroed).isEqualTo(1);
    assertThat(disclosuresEncOf(expired))
        .as("expired unclaimed code must be zeroed (disclosures_enc now NULL)")
        .isNull();
    assertThat(disclosuresEncOf(unexpired)).as("unexpired code must be untouched").isNotNull();
    assertThat(disclosuresEncOf(claimed)).as("already-claimed code must be untouched").isNotNull();

    // Exactly one new CLAIM_CODES_EXPIRED audit row carrying the count.
    Integer auditCountAfter = countClaimCodesExpiredAuditRows();
    assertThat(auditCountAfter - auditCountBefore).isEqualTo(1);
    String detailCount =
        jdbc.queryForObject(
            "SELECT detail->>'count' FROM audit_log WHERE action = 'CLAIM_CODES_EXPIRED' "
                + "ORDER BY occurred_at DESC LIMIT 1",
            String.class);
    assertThat(detailCount).isEqualTo("1");
  }

  @Test
  @Transactional
  void sweep_withNothingExpired_writesNoAuditRow() {
    IssueResponse issued =
        credentialService.issue(
            new IssueRequest(
                "SweepNone/v1",
                "holder-sweep-none-" + UUID.randomUUID(),
                1,
                60,
                Map.of("result", "NO_RECORD"),
                List.of()));
    UUID credentialId = UUID.fromString(issued.id());
    entityManager.flush();
    // Only an unexpired, populated code — nothing to zero.
    insertClaimCode(
        credentialId,
        TenantContext.current(),
        sha256("dummy-" + UUID.randomUUID()),
        Instant.now().plus(1, ChronoUnit.HOURS),
        null);

    // See the sibling test's comment on why this must be a delta, not an absolute count.
    Integer auditCountBefore = countClaimCodesExpiredAuditRows();
    int zeroed = worker.sweep();

    assertThat(zeroed).isZero();
    Integer auditCountAfter = countClaimCodesExpiredAuditRows();
    assertThat(auditCountAfter - auditCountBefore)
        .as("no audit row when nothing was zeroed")
        .isZero();
  }

  private Integer countClaimCodesExpiredAuditRows() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action = 'CLAIM_CODES_EXPIRED'", Integer.class);
  }

  private UUID insertClaimCode(
      UUID credentialId, UUID tenant, byte[] disclosuresEnc, Instant expiresAt, Instant claimedAt) {
    UUID id = Uuidv7.generate();
    jdbc.update(
        "INSERT INTO claim_code (id, tenant_id, credential_id, code_hash, disclosures_enc,"
            + " expires_at, claimed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        tenant,
        credentialId,
        sha256("code-" + UUID.randomUUID()),
        disclosuresEnc,
        Timestamp.from(expiresAt),
        claimedAt == null ? null : Timestamp.from(claimedAt),
        Timestamp.from(Instant.now()));
    return id;
  }

  private byte[] disclosuresEncOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT disclosures_enc FROM claim_code WHERE id = ?", byte[].class, id);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
    }
  }
}
