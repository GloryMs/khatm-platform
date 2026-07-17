package sy.khatm.platform.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * One append-only {@code audit_log} row (spec FS-0.2 §3.11, NFR-08).
 *
 * <p>Unlike every other entity in the platform, the id is a database-generated {@code bigint}
 * identity, not an application-generated UUIDv7 — {@code audit_log} needs strict insertion ordering
 * for a tenant's timeline, which an identity column gives for free. {@code detail} is raw JSON text
 * (never claims, keys, or PII — SEC §9.7); {@code null} when the action has no extra detail to
 * record.
 *
 * <p>Package-private — only {@link AuditService} in this package touches this entity directly.
 */
@Entity
@Table(name = "audit_log")
class AuditLogEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "actor_type", nullable = false)
  private String actorType;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(nullable = false)
  private String action;

  @Column(name = "entity_type", nullable = false)
  private String entityType;

  @Column(name = "entity_ref")
  private String entityRef;

  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String detail;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  Long getId() {
    return id;
  }

  UUID getTenantId() {
    return tenantId;
  }

  void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  String getActorType() {
    return actorType;
  }

  void setActorType(String actorType) {
    this.actorType = actorType;
  }

  UUID getActorId() {
    return actorId;
  }

  void setActorId(UUID actorId) {
    this.actorId = actorId;
  }

  String getAction() {
    return action;
  }

  void setAction(String action) {
    this.action = action;
  }

  String getEntityType() {
    return entityType;
  }

  void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  String getEntityRef() {
    return entityRef;
  }

  void setEntityRef(String entityRef) {
    this.entityRef = entityRef;
  }

  String getDetail() {
    return detail;
  }

  void setDetail(String detail) {
    this.detail = detail;
  }

  Instant getOccurredAt() {
    return occurredAt;
  }

  void setOccurredAt(Instant occurredAt) {
    this.occurredAt = occurredAt;
  }
}
