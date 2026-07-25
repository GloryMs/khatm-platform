package sy.khatm.platform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.1.5-BE, spec FS-1.5.4 — {@link AuditService#dailyActionCounts}, {@link
 * AuditService#recentEvents}, and {@link AuditService#actorActionCounts}: the three Dashboard v2
 * reads added alongside {@link AuditService#countActionsInWindow} (already covered by {@link
 * AuditStatsTest}). Rows are seeded directly via JDBC exactly like that suite, for the same reason
 * (pinning {@code occurred_at} exactly; the append-only trigger only blocks {@code UPDATE}/{@code
 * DELETE}).
 */
class AuditServiceDashboardTest extends IntegrationTestSupport {

  @Autowired private AuditService auditService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void dailyActionCounts_bucketsByUtcDay() {
    Instant today = Instant.now();
    Instant yesterday = today.minus(1, ChronoUnit.DAYS);
    String probe = "daily-probe-" + UUID.randomUUID();

    insertRow("CREDENTIAL_ISSUED", probe, today, null);
    insertRow("CREDENTIAL_ISSUED", probe, today, null);
    insertRow("CREDENTIAL_ISSUED", probe, yesterday, null);

    Map<Instant, Map<String, Long>> byDay =
        auditService.dailyActionCounts(yesterday.minus(1, ChronoUnit.DAYS), today.plusSeconds(5));

    Instant todayMidnight = today.truncatedTo(ChronoUnit.DAYS);
    Instant yesterdayMidnight = yesterday.truncatedTo(ChronoUnit.DAYS);

    assertThat(byDay.get(todayMidnight)).isNotNull();
    assertThat(byDay.get(todayMidnight).getOrDefault("CREDENTIAL_ISSUED", 0L))
        .isGreaterThanOrEqualTo(2L);
    assertThat(byDay.get(yesterdayMidnight)).isNotNull();
    assertThat(byDay.get(yesterdayMidnight).getOrDefault("CREDENTIAL_ISSUED", 0L))
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  void recentEvents_ordersNewestFirst() {
    // This shared-context suite's audit_log accumulates rows from every other test class that ran
    // before or concurrently with this one (same caveat as AuditStatsTest), so a small `limit` is
    // not reliable here — the tenant-wide "N most recent" may not include this test's own probe
    // rows. A generous limit reliably captures them; ordering among them is what this test checks.
    String probe = "recent-probe-" + UUID.randomUUID();
    Instant t1 = Instant.now().minusSeconds(30);
    Instant t2 = Instant.now().minusSeconds(20);
    Instant t3 = Instant.now().minusSeconds(10);
    insertRow("CREDENTIAL_ISSUED", probe + "-1", t1, null);
    insertRow("CREDENTIAL_ISSUED", probe + "-2", t2, null);
    insertRow("CREDENTIAL_ISSUED", probe + "-3", t3, null);

    List<AuditEventView> rows =
        auditService.recentEvents(1000, List.of("CREDENTIAL_ISSUED")).stream()
            .filter(r -> r.entityRef() != null && r.entityRef().startsWith(probe))
            .toList();

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).entityRef()).isEqualTo(probe + "-3");
    assertThat(rows.get(1).entityRef()).isEqualTo(probe + "-2");
    assertThat(rows.get(2).entityRef()).isEqualTo(probe + "-1");
  }

  @Test
  void recentEvents_respectsLimit() {
    assertThat(auditService.recentEvents(1, null)).hasSize(1);
    assertThat(auditService.recentEvents(3, null)).hasSize(3);
  }

  @Test
  void recentEvents_withNoActionFilter_includesEveryAction() {
    String probe = "recent-nofilter-" + UUID.randomUUID();
    insertRow("CREDENTIAL_REVOKED", probe, Instant.now(), null);

    List<AuditEventView> rows = auditService.recentEvents(500, null);

    assertThat(rows.stream().anyMatch(r -> probe.equals(r.entityRef()))).isTrue();
  }

  @Test
  void recentEvents_parsesDetailJson() {
    String probe = "recent-detail-" + UUID.randomUUID();
    insertRow("CONSUME_SCHEMA_DENIED", probe, Instant.now(), "{\"schemaId\":\"abc\"}");

    List<AuditEventView> rows = auditService.recentEvents(500, List.of("CONSUME_SCHEMA_DENIED"));

    AuditEventView row =
        rows.stream().filter(r -> probe.equals(r.entityRef())).findFirst().orElseThrow();
    assertThat(row.detail()).containsEntry("schemaId", "abc");
  }

  @Test
  void actorActionCounts_groupsByActorAndAction_apiKeyRowsOnly() {
    UUID actorId = UUID.randomUUID();
    Instant now = Instant.now();
    Instant from = now.minusSeconds(5);
    Instant to = now.plusSeconds(5);

    insertApiKeyRow("CREDENTIAL_CONSUMED", "cred-1", now, actorId);
    insertApiKeyRow("CREDENTIAL_CONSUMED", "cred-2", now, actorId);
    insertApiKeyRow("CONSUME_SCHEMA_DENIED", "cred-3", now, actorId);
    // A SYSTEM-attributed row for the same action, in the same window — must not pollute the
    // API_KEY-only aggregation.
    insertRow("CREDENTIAL_CONSUMED", "cred-4", now, null);

    Map<UUID, Map<String, Long>> byActor =
        auditService.actorActionCounts(
            from, to, List.of("CREDENTIAL_CONSUMED", "CONSUME_SCHEMA_DENIED"));

    assertThat(byActor.get(actorId).get("CREDENTIAL_CONSUMED")).isEqualTo(2L);
    assertThat(byActor.get(actorId).get("CONSUME_SCHEMA_DENIED")).isEqualTo(1L);
  }

  private void insertRow(String action, String entityRef, Instant occurredAt, String detail) {
    jdbc.update(
        "INSERT INTO audit_log (tenant_id, actor_type, action, entity_type, entity_ref,"
            + " detail, occurred_at) VALUES (?, 'SYSTEM', ?, 'credential', ?, ?::jsonb, ?)",
        TenantContext.current(),
        action,
        entityRef,
        detail,
        Timestamp.from(occurredAt));
  }

  private void insertApiKeyRow(String action, String entityRef, Instant occurredAt, UUID actorId) {
    jdbc.update(
        "INSERT INTO audit_log (tenant_id, actor_type, actor_id, action, entity_type, entity_ref,"
            + " occurred_at) VALUES (?, 'API_KEY', ?, ?, 'credential', ?, ?)",
        TenantContext.current(),
        actorId,
        action,
        entityRef,
        Timestamp.from(occurredAt));
  }
}
