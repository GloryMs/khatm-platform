package sy.khatm.platform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
