package sy.khatm.platform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.shared.TenantContext;

/**
 * The single write path into {@code audit_log} (spec FS-0.6b D8) — no module writes an {@code
 * INSERT INTO audit_log} directly; every event goes through {@link #record}.
 *
 * <p><b>Actor inference:</b> the actor is read from {@code SecurityContextHolder}'s current {@link
 * Authentication}. When its principal implements {@link AuditPrincipal}, the row is attributed to
 * that {@code USER} or {@code API_KEY}; otherwise (no session, no API key, a scheduled worker task,
 * a startup bootstrap runner) the row is attributed to {@code SYSTEM} with a {@code null actorId}.
 *
 * <p><b>Atomicity (spec FS-0.6b D8/NFR-08):</b> {@code @Transactional} with the default {@code
 * REQUIRED} propagation — a call from inside an already-{@code @Transactional} method (issuing a
 * credential, rotating a key, logging in) joins that same physical transaction, so the audit row
 * and the operation it describes commit or roll back together; there is never an event without its
 * audit row, nor an audit row for an event that never actually committed.
 *
 * <p>Never logs or stores claims, key material, passwords, or API key secrets — {@code detail} is
 * for non-sensitive structured context only (a count, a reason code, a key prefix — SEC §9.7).
 */
@Component
public class AuditService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ACTOR_SYSTEM = "SYSTEM";

  private final AuditLogRepository repository;

  AuditService(AuditLogRepository repository) {
    this.repository = repository;
  }

  /**
   * Record one audit event in the current transaction.
   *
   * @param action the catalog event being recorded
   * @param entityType the kind of entity this event concerns (e.g. {@code "credential"}, {@code
   *     "app_user"}) — a short, lowercase, non-i18n label, not a display string
   * @param entityRef an opaque reference identifying the specific entity (a ref, a kid, a key
   *     prefix, an id as a string) — never a secret, a claim value, or PII; may be {@code null}
   *     when the action has no single entity to point at
   * @param detail additional non-sensitive structured context (e.g. {@code {"count": 3}}); may be
   *     {@code null} or empty when the action needs none
   */
  @Transactional
  public void record(
      AuditAction action, String entityType, String entityRef, Map<String, Object> detail) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    String actorType = ACTOR_SYSTEM;
    UUID actorId = null;
    if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuditPrincipal p) {
      actorType = p.auditActorType();
      actorId = p.auditActorId();
    }

    AuditLogEntry entry = new AuditLogEntry();
    entry.setTenantId(TenantContext.current());
    entry.setActorType(actorType);
    entry.setActorId(actorId);
    entry.setAction(action.name());
    entry.setEntityType(entityType);
    entry.setEntityRef(entityRef);
    entry.setDetail(detail == null || detail.isEmpty() ? null : writeJson(detail));
    entry.setOccurredAt(Instant.now());
    repository.save(entry);
  }

  /**
   * Count {@code audit_log} rows per {@link AuditAction#name()} for the current tenant within
   * {@code [from, to)} (KH-1.1.3, {@code GET /api/v1/stats}) — a plain read-only aggregation over
   * the same append-only trail every other action already writes to, not a new bookkeeping system.
   *
   * @param from inclusive start of the window
   * @param to exclusive end of the window
   * @return a map from the raw {@code action} string (e.g. {@code "CREDENTIAL_ISSUED"}) to its
   *     count within the window; an action with zero occurrences is simply absent, never a zero
   *     entry
   */
  @Transactional(readOnly = true)
  public Map<String, Long> countActionsInWindow(Instant from, Instant to) {
    List<Object[]> rows = repository.countByActionInWindow(TenantContext.current(), from, to);
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Object[] row : rows) {
      counts.put((String) row[0], ((Number) row[1]).longValue());
    }
    return counts;
  }

  /**
   * Count {@code audit_log} rows per UTC day per {@link AuditAction#name()} within {@code [from,
   * to)} (spec FS-1.5.4 #1, {@code GET /api/v1/stats/daily}) — the per-day breakdown behind the
   * console's lifecycle chart, same aggregation as {@link #countActionsInWindow} bucketed by day.
   *
   * @return an ordered map from each UTC-midnight day instant that had at least one event to that
   *     day's per-action counts; a day/action pair with zero occurrences is simply absent
   */
  @Transactional(readOnly = true)
  public Map<Instant, Map<String, Long>> dailyActionCounts(Instant from, Instant to) {
    List<Object[]> rows = repository.countByDayAndActionInWindow(TenantContext.current(), from, to);
    Map<Instant, Map<String, Long>> byDay = new LinkedHashMap<>();
    for (Object[] row : rows) {
      Instant day = toInstant(row[0]);
      String action = (String) row[1];
      long count = ((Number) row[2]).longValue();
      byDay.computeIfAbsent(day, k -> new LinkedHashMap<>()).put(action, count);
    }
    return byDay;
  }

  /**
   * Normalizes a native query's {@code timestamptz} projection to {@link Instant} — Hibernate's
   * JDBC type mapping for a plain {@code Object[]} projection has returned either {@link Instant}
   * directly or a {@link Timestamp} depending on driver/dialect version, so both are tolerated
   * rather than assuming one.
   */
  private static Instant toInstant(Object value) {
    return value instanceof Timestamp timestamp ? timestamp.toInstant() : (Instant) value;
  }

  /**
   * The most recent {@code audit_log} rows for the current tenant, newest first (spec FS-1.5.4 #2,
   * {@code GET /api/v1/activity}) — raw rows only, no display resolution (entity ref, actor display
   * name); see {@link AuditEventView}'s Javadoc for why that is deliberately the caller's job.
   *
   * @param limit the maximum number of rows to return
   * @param actions the actions to include, or {@code null}/empty to include every action
   */
  @Transactional(readOnly = true)
  public List<AuditEventView> recentEvents(int limit, List<String> actions) {
    List<String> filter = (actions == null || actions.isEmpty()) ? null : actions;
    return repository
        .findRecent(TenantContext.current(), filter, PageRequest.ofSize(limit))
        .stream()
        .map(AuditService::toView)
        .toList();
  }

  /**
   * Count {@code audit_log} rows per {@code API_KEY} actor per {@link AuditAction#name()} within
   * {@code [from, to)} (spec FS-1.5.4 "also needed", {@code GET /api/v1/stats/consuming-parties}) —
   * {@code actorId} is an {@code api_key.id}; resolving it to a consuming party's display name is
   * the caller's job (spec D2/D4), not this class's.
   *
   * @param actions the actions to aggregate (e.g. {@code CREDENTIAL_CONSUMED}, {@code
   *     CONSUME_SCHEMA_DENIED})
   * @return a map from each {@code api_key} actor id to its per-action counts within the window
   */
  @Transactional(readOnly = true)
  public Map<UUID, Map<String, Long>> actorActionCounts(
      Instant from, Instant to, List<String> actions) {
    List<Object[]> rows =
        repository.countByActorAndActionInWindow(TenantContext.current(), from, to, actions);
    Map<UUID, Map<String, Long>> byActor = new LinkedHashMap<>();
    for (Object[] row : rows) {
      UUID actorId = (UUID) row[0];
      String action = (String) row[1];
      long count = ((Number) row[2]).longValue();
      byActor.computeIfAbsent(actorId, k -> new LinkedHashMap<>()).put(action, count);
    }
    return byActor;
  }

  private static AuditEventView toView(AuditLogEntry entry) {
    return new AuditEventView(
        entry.getAction(),
        entry.getActorType(),
        entry.getActorId(),
        entry.getEntityRef(),
        readJson(entry.getDetail()),
        entry.getOccurredAt());
  }

  private static Map<String, Object> readJson(String detail) {
    if (detail == null) {
      return null;
    }
    try {
      return JSON.readValue(detail, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      // detail is only ever written by writeJson() below (small maps of primitives/strings) — a
      // failure here means a stored row is corrupt, not bad caller input.
      throw new UncheckedIOException("Failed to parse audit detail as JSON", e);
    }
  }

  private static String writeJson(Map<String, Object> detail) {
    try {
      return JSON.writeValueAsString(detail);
    } catch (JsonProcessingException e) {
      // detail is always our own construction (small maps of primitives/strings) — a failure
      // here means an internal invariant broke, not bad caller input.
      throw new UncheckedIOException("Failed to serialize audit detail to JSON", e);
    }
  }
}
