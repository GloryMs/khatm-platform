package sy.khatm.poc.credential;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface CredentialRepository extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByRef(String ref);

    /**
     * Atomic, single-statement decrement. Returns the number of rows updated:
     *  1 = consumed successfully, 0 = rejected (no uses left / revoked / expired).
     * This is what prevents the double-spend problem — the row lock serialises
     * concurrent consumers, so the same copy can never be consumed twice.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE credential
               SET uses_remaining = uses_remaining - 1
             WHERE id = :id
               AND uses_remaining > 0
               AND revoked = false
               AND now() BETWEEN valid_from AND valid_to
            """, nativeQuery = true)
    int consumeOne(@Param("id") UUID id);
}

interface ConsumptionEventRepository extends JpaRepository<ConsumptionEvent, UUID> {
}
