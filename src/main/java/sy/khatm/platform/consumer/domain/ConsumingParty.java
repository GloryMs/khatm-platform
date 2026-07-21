package sy.khatm.platform.consumer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.domain.Persistable;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.LocalizedTextConverter;

/**
 * A verifier/relying party permitted to consume credentials.
 *
 * <p>API-key authentication for a consuming party is the {@code rbac} module's {@code api_key}
 * table (owner_type {@code CONSUMING_PARTY}, spec FS-0.6b D3) — this entity carries no key hash of
 * its own (KH-0.2.1's {@code api_key_hash} stand-in column was dropped by {@code
 * V2__auth_api_keys.sql}). The admin plane (KH-1.4.4, {@code /api/v1/admin/consuming-parties})
 * registers parties, flips their {@link #status} between {@code ACTIVE} and {@code SUSPENDED}, and
 * scopes them to schemas via {@code consuming_party_schema}.
 *
 * <p><b>Deterministic id / forced INSERT:</b> the id is derived from {@code (tenant, code)} rather
 * than time-ordered, so the same code always resolves to the same row (find-or-create by id). To
 * keep {@code ConsumingPartyRegistry#ensure}'s find-or-create race deterministic (KH-1.4.4 D6),
 * this entity implements {@link Persistable} so a freshly built instance is always {@code
 * em.persist()}ed (a true {@code INSERT} that fails cleanly with a duplicate-key violation on a
 * lost race), never silently upgraded to a {@code merge}/{@code SELECT}+{@code UPDATE} that could
 * clobber the winner's row. A loaded/persisted instance reports {@code isNew() == false}, so {@code
 * #suspend}/{@code #activate} updates still work through the normal merge path.
 *
 * <p>This class is module-private; external code must depend on {@code consumer :: api} instead.
 */
@Entity
@Table(name = "consuming_party")
public class ConsumingParty implements Persistable<UUID> {

  /** Active — the party's keys authenticate normally. */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** Suspended — the party's keys fail authentication (KH-1.4.4 D4), like a revoked key. */
  public static final String STATUS_SUSPENDED = "SUSPENDED";

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, updatable = false)
  private String code;

  @Convert(converter = LocalizedTextConverter.class)
  @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LocalizedText nameI18n;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Transient private boolean isNew = true;

  @Override
  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  /**
   * Whether this instance has never been persisted — {@code true} for a freshly constructed row,
   * {@code false} once loaded or inserted, so Spring Data JPA issues an {@code INSERT} for new rows
   * and a {@code merge} for existing ones (see the class Javadoc).
   *
   * @return {@code true} if this row has not yet been written to the database
   */
  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public LocalizedText getNameI18n() {
    return nameI18n;
  }

  public void setNameI18n(LocalizedText nameI18n) {
    this.nameI18n = nameI18n;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
