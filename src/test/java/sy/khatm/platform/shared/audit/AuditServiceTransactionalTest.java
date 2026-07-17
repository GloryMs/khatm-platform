package sy.khatm.platform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * Spec FS-0.6b DoD #8 — {@link AuditService#record} joins the caller's own transaction ({@code
 * REQUIRED} propagation, the default): if the caller's transaction rolls back after {@code
 * record(...)} runs, the audit row rolls back with it. No event ever leaves an orphaned audit row
 * behind for an operation that never actually committed (D8/NFR-08).
 */
class AuditServiceTransactionalTest extends IntegrationTestSupport {

  @Autowired private AuditService auditService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void recordedRow_rollsBackWithTheCallersTransaction_whenTheCallerFailsAfterward() {
    String entityRef = "rollback-probe-" + UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      auditService.record(AuditAction.USER_CREATED, "test", entityRef, null);
                      throw new IllegalStateException(
                          "forced failure after record() — must roll back the audit row too");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("forced failure");

    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_ref = ?", Integer.class, entityRef);
    assertThat(count)
        .as("no orphaned audit row after the caller's transaction rolled back")
        .isZero();
  }

  @Test
  void recordedRow_survives_whenTheCallersTransactionCommits() {
    String entityRef = "commit-probe-" + UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    transactionTemplate.executeWithoutResult(
        status -> auditService.record(AuditAction.USER_CREATED, "test", entityRef, null));

    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_ref = ?", Integer.class, entityRef);
    assertThat(count).isEqualTo(1);
  }
}
