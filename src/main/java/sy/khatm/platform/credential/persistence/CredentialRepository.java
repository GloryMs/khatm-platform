package sy.khatm.platform.credential.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.credential.domain.Credential;

/**
 * Repository for {@link Credential} entities.
 *
 * <p>Module-private — only {@code CredentialService} in the domain sub-package may use this.
 *
 * <p>KH-2.1 Part B (spec FS-2.1 D4): type-level {@code @Transactional(readOnly = true)} — see
 * {@code key.persistence.IssuerKeyRepository}'s Javadoc for the full rationale. {@link
 * #findSchemaId} in particular is called from {@code CredentialService#enforceSchemaAllowlist},
 * which is deliberately non-{@code @Transactional} itself (its audit-row write must commit
 * independently of the authorization exception it's about to throw) — this annotation is what makes
 * that one bare call RLS-safe without touching that design.
 */
@Transactional(readOnly = true)
public interface CredentialRepository extends JpaRepository<Credential, UUID> {

  Optional<Credential> findByRef(String ref);

  /**
   * Atomic, single-statement use decrement. Returns the number of rows updated: 1 = success, 0 =
   * rejected (no uses left / revoked / outside validity window).
   *
   * <p>This native UPDATE with all eligibility conditions in the WHERE clause is the sole mechanism
   * that prevents the double-spend problem. Row-level locking by the DB serialises concurrent
   * callers, so the same credential can never be consumed more than {@code max_uses} times.
   *
   * <p>Native SQL is intentional here — JPQL does not support {@code now() BETWEEN ...} in a single
   * UPDATE with conditional decrement.
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          UPDATE credential
             SET uses_remaining = uses_remaining - 1
           WHERE id = :id
             AND uses_remaining > 0
             AND revoked = false
             AND now() BETWEEN valid_from AND valid_to
          """,
      nativeQuery = true)
  int consumeOne(@Param("id") UUID id);

  /**
   * A credential's {@code schema_id} alone, by primary key (KH-1.4.3) — {@code
   * CredentialService#consume}'s schema-allowlist pre-check reads only this one column rather than
   * the whole entity (which also carries {@code signed_payload}), keeping the common/allowed path
   * to exactly one lean indexed read ahead of the atomic {@link #consumeOne} update.
   */
  @Query(value = "SELECT schema_id FROM credential WHERE id = :id", nativeQuery = true)
  Optional<UUID> findSchemaId(@Param("id") UUID id);

  /**
   * KH-1.1.4 — {@code GET /api/v1/credentials}'s search/list query: every filter is optional and
   * AND-combined (a {@code null} parameter matches every row), sorted by {@code createdAt} DESC
   * (the {@code credential_tenant_created} index, added alongside this method in {@code
   * V4__schema_archive_and_credential_search_index.sql}, drives both the base tenant-scoped scan
   * and its sort). {@code pseudoRef} is resolved to a {@code holderId} by the caller ({@link
   * sy.khatm.platform.holder.api.HolderDirectory#findByPseudoRef}, a cross-module lookup this
   * repository cannot perform itself) before this query runs.
   *
   * <p><b>{@code status} filter (KH-1.6-BE follow-up, chore/credential-search-status-filter):</b>
   * the inline {@code CASE} expression is the SQL mirror of {@code
   * sy.khatm.platform.credential.domain.CredentialStatus#derive} — same precedence order ({@code
   * REVOKED} > {@code EXHAUSTED} > {@code EXPIRED} > {@code ACTIVE}), same {@code now} instant the
   * caller also uses to derive the row's own displayed {@code status} field (passed in as {@code
   * :now} rather than using SQL {@code CURRENT_TIMESTAMP}, precisely so a row can never show status
   * X while being filtered out of X — both are computed against the identical instant). {@code
   * statuses} is never {@code null}/empty by contract — the caller ({@code
   * CredentialService#search}) always supplies every {@link
   * sy.khatm.platform.credential.domain.CredentialStatus} name when the caller requested no
   * filtering, so this method never needs its own null-collection branch on an {@code IN} clause.
   */
  @Query(
      """
      SELECT c FROM Credential c
       WHERE c.tenantId = :tenantId
         AND (:ref IS NULL OR c.ref = :ref)
         AND (:holderId IS NULL OR c.holderId = :holderId)
         AND (:schemaId IS NULL OR c.schemaId = :schemaId)
         AND (:revoked IS NULL OR c.revoked = :revoked)
         AND CASE
               WHEN c.revoked = true THEN 'REVOKED'
               WHEN c.usesRemaining <= 0 THEN 'EXHAUSTED'
               WHEN c.validTo < :now THEN 'EXPIRED'
               ELSE 'ACTIVE'
             END IN :statuses
       ORDER BY c.createdAt DESC
      """)
  Page<Credential> search(
      @Param("tenantId") UUID tenantId,
      @Param("ref") String ref,
      @Param("holderId") UUID holderId,
      @Param("schemaId") UUID schemaId,
      @Param("revoked") Boolean revoked,
      @Param("statuses") List<String> statuses,
      @Param("now") Instant now,
      Pageable pageable);
}
