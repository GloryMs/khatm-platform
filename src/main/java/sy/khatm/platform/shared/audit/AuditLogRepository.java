package sy.khatm.platform.shared.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link AuditLogEntry} rows.
 *
 * <p>Package-private — only {@link AuditService} may write through this; {@code audit_log} itself
 * additionally rejects {@code UPDATE}/{@code DELETE} at the database level (append-only trigger,
 * {@code AuditLogAppendOnlyTest}). {@link #countByActionInWindow} (KH-1.1.3, the stats endpoint's
 * only read of this table) is the one read query this interface needs beyond {@code save}.
 */
interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

  /**
   * Count rows per {@code action} for one tenant within {@code [from, to)} — the aggregation the
   * stats endpoint (KH-1.1.3, spec FS-1.5.3) is built on, backed by {@code
   * audit_log_tenant_occurred_idx} (V6).
   *
   * @return one {@code [action, count]} pair per distinct action that occurred at least once in the
   *     window
   */
  @Query(
      value =
          "SELECT action, COUNT(*) FROM audit_log "
              + "WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :to "
              + "GROUP BY action",
      nativeQuery = true)
  List<Object[]> countByActionInWindow(
      @Param("tenantId") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to);
}
