package sy.khatm.platform.credential.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sy.khatm.platform.credential.domain.Credential;

/**
 * Repository for {@link Credential} entities.
 *
 * <p>Module-private — only {@code CredentialService} in the domain sub-package may use this.
 */
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
}
