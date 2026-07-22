package sy.khatm.platform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.1.3 — {@link AuditService#countActionsInWindow}, the stats endpoint's own aggregation. Rows
 * are inserted directly via JDBC (bypassing {@link AuditService#record}) so their {@code
 * occurred_at} can be pinned exactly — {@code audit_log}'s append-only trigger only blocks {@code
 * UPDATE}/{@code DELETE}, never {@code INSERT}, so this stays a legitimate way to seed fixed
 * timestamps for a window-filtering test.
 *
 * <p>Every assertion here is a <em>delta</em> (count after minus count before), never a bare count
 * — this shared-context suite's {@code audit_log} accumulates rows from every other test class that
 * ran before or concurrently with this one, so an exact tenant-wide count for a wide window would
 * be flaky by construction.
 */
class AuditStatsTest extends IntegrationTestSupport {

  @Autowired private AuditService auditService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void countActionsInWindow_groupsByActionAndCountsCorrectly() {
    Instant now = Instant.now();
    Instant from = now.minusSeconds(2);
    Instant to = now.plusSeconds(2);
    String probe = "count-probe-" + UUID.randomUUID();

    long issuedBefore = countIn(from, to, "CREDENTIAL_ISSUED");
    long revokedBefore = countIn(from, to, "CREDENTIAL_REVOKED");

    insertRow("CREDENTIAL_ISSUED", probe, now);
    insertRow("CREDENTIAL_ISSUED", probe, now);
    insertRow("CREDENTIAL_ISSUED", probe, now);
    insertRow("CREDENTIAL_REVOKED", probe, now);

    Map<String, Long> counts = auditService.countActionsInWindow(from, to);

    assertThat(counts.getOrDefault("CREDENTIAL_ISSUED", 0L) - issuedBefore).isEqualTo(3L);
    assertThat(counts.getOrDefault("CREDENTIAL_REVOKED", 0L) - revokedBefore).isEqualTo(1L);
  }

  @Test
  void countActionsInWindow_excludesRowsOutsideTheWindow() {
    Instant now = Instant.now();
    Instant from = now.minus(30, ChronoUnit.DAYS);
    Instant to = now.plusSeconds(5);
    String probe = "window-probe-" + UUID.randomUUID();

    long before = countIn(from, to, "CREDENTIAL_CONSUMED");

    // Outside the window (older than `from`) — must not be counted.
    insertRow("CREDENTIAL_CONSUMED", probe, now.minus(40, ChronoUnit.DAYS));
    // Inside the window — must be counted exactly once.
    insertRow("CREDENTIAL_CONSUMED", probe, now);

    Map<String, Long> counts = auditService.countActionsInWindow(from, to);

    assertThat(counts.getOrDefault("CREDENTIAL_CONSUMED", 0L) - before).isEqualTo(1L);
  }

  @Test
  void countActionsInWindow_toBoundIsExclusive() {
    Instant boundary = Instant.now();
    String probe = "exclusive-probe-" + UUID.randomUUID();

    long before = countIn(boundary.minusSeconds(60), boundary, "CLAIM_CODE_REDEEMED");

    // occurred_at == `to` exactly — the window is [from, to), so this must be excluded.
    insertRow("CLAIM_CODE_REDEEMED", probe, boundary);

    Map<String, Long> counts =
        auditService.countActionsInWindow(boundary.minusSeconds(60), boundary);

    assertThat(counts.getOrDefault("CLAIM_CODE_REDEEMED", 0L) - before).isZero();
  }

  private long countIn(Instant from, Instant to, String action) {
    return auditService.countActionsInWindow(from, to).getOrDefault(action, 0L);
  }

  private void insertRow(String action, String entityRef, Instant occurredAt) {
    jdbc.update(
        "INSERT INTO audit_log (tenant_id, actor_type, action, entity_type, entity_ref,"
            + " occurred_at) VALUES (?, 'SYSTEM', ?, 'credential', ?, ?)",
        TenantContext.current(),
        action,
        entityRef,
        Timestamp.from(occurredAt));
  }
}
