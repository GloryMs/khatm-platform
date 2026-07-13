package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.2 §5 acceptance criterion 5 — {@code UPDATE}/{@code DELETE} on {@code audit_log} are
 * rejected by the {@code audit_log_no_update} trigger (append-only, NFR-08).
 *
 * <p>Uses plain JDBC rather than an entity because no Java code writes to {@code audit_log} yet
 * (see {@code shared}'s README) — the trigger itself is what this test verifies, independent of any
 * future write path.
 */
class AuditLogAppendOnlyTest extends IntegrationTestSupport {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void update_onAuditLogRow_isRejectedByTrigger() {
    long id = insertProbeRow();

    assertThatThrownBy(
            () -> jdbcTemplate.update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = ?", id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void delete_onAuditLogRow_isRejectedByTrigger() {
    long id = insertProbeRow();

    assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  private long insertProbeRow() {
    UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO audit_log (tenant_id, actor_type, action, entity_type, entity_ref)
        VALUES (?, 'SYSTEM', 'PROBE_INSERTED', 'test', 'audit-log-probe')
        RETURNING id
        """,
        Long.class,
        tenantId);
  }
}
