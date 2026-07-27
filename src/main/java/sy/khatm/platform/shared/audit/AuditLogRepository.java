package sy.khatm.platform.shared.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for {@link AuditLogEntry} rows.
 *
 * <p>Package-private — only {@link AuditService} may write through this; {@code audit_log} itself
 * additionally rejects {@code UPDATE}/{@code DELETE} at the database level (append-only trigger,
 * {@code AuditLogAppendOnlyTest}). {@link #countByActionInWindow} (KH-1.1.3, the stats endpoint's
 * only read of this table) was the first read query this interface needed beyond {@code save};
 * {@link #countByDayAndActionInWindow}, {@link #findRecent}, and {@link
 * #countByActorAndActionInWindow} (KH-1.1.5-BE, spec FS-1.5.4) are Dashboard v2's reads.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale.
 */
@Transactional(readOnly = true)
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

  /**
   * Count rows per UTC day per {@code action} within {@code [from, to)} (spec FS-1.5.4 #1, {@code
   * GET /api/v1/stats/daily}) — the per-day breakdown behind the console's lifecycle chart. Day
   * boundaries are forced to UTC ({@code AT TIME ZONE 'UTC'}) regardless of the JDBC connection's
   * session timezone, matching this platform's UTC-everywhere convention (CLAUDE.md).
   *
   * @return one {@code [day, action, count]} triple per distinct (day, action) pair that occurred
   *     at least once in the window, ordered by day then action; {@code day} is a UTC midnight
   *     instant (round-tripped back through {@code AT TIME ZONE 'UTC'} so the JDBC driver returns a
   *     proper {@code timestamptz} instant, not a session-timezone-dependent wall clock)
   */
  @Query(
      value =
          "SELECT date_trunc('day', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS day,"
              + " action, COUNT(*) "
              + "FROM audit_log "
              + "WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :to "
              + "GROUP BY day, action "
              + "ORDER BY day, action",
      nativeQuery = true)
  List<Object[]> countByDayAndActionInWindow(
      @Param("tenantId") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * The most recent rows for one tenant, newest first, optionally filtered to a set of actions
   * (spec FS-1.5.4 #2, {@code GET /api/v1/activity}) — backed by {@code
   * audit_log_tenant_occurred_idx} (V6) for the tenant-scoped scan; the {@code ORDER BY} then LIMIT
   * (via {@code pageable}) keeps this a bounded read regardless of table size.
   *
   * @param actions the actions to include, or {@code null}/empty to include every action
   * @param pageable supplies the row limit ({@code Pageable#ofSize}); sort is ignored, always
   *     {@code occurred_at DESC}
   */
  @Query(
      "SELECT e FROM AuditLogEntry e "
          + "WHERE e.tenantId = :tenantId AND (:actions IS NULL OR e.action IN :actions) "
          + "ORDER BY e.occurredAt DESC")
  List<AuditLogEntry> findRecent(
      @Param("tenantId") UUID tenantId, @Param("actions") List<String> actions, Pageable pageable);

  /**
   * Count rows per {@code actor_id} per {@code action} within {@code [from, to)}, {@code
   * API_KEY}-attributed rows only (spec FS-1.5.4 "also needed", {@code GET
   * /api/v1/stats/consuming-parties}) — {@code actor_id} here is an {@code api_key.id}, resolved to
   * its owning consuming party by {@code rbac :: api ApiKeyOwnerLookup} (spec D2), never joined
   * directly.
   *
   * @param actions the actions to aggregate (e.g. {@code CREDENTIAL_CONSUMED}, {@code
   *     CONSUME_SCHEMA_DENIED})
   * @return one {@code [actorId, action, count]} triple per distinct (actor, action) pair
   */
  @Query(
      value =
          "SELECT actor_id, action, COUNT(*) FROM audit_log "
              + "WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :to "
              + "AND actor_type = 'API_KEY' AND action IN (:actions) "
              + "GROUP BY actor_id, action",
      nativeQuery = true)
  List<Object[]> countByActorAndActionInWindow(
      @Param("tenantId") UUID tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("actions") List<String> actions);
}
