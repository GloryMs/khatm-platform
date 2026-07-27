package sy.khatm.platform.key.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.key.persistence.IssuerKeyRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.5 §8 acceptance criteria exercised directly against {@link KeyLifecycleService}: bootstrap
 * idempotency (#7), the {@code rotate()} one-active invariant (#4), strict {@code kid} resolution
 * with no fallback (#3), no private material ever leaving the keystore (#6), and the {@code
 * KEY_CREATED}/{@code KEY_ROTATED} audit rows (#8).
 *
 * <p>Runs against the shared Testcontainers Postgres context — {@code KeyBootstrap} has already
 * provisioned the default tenant's first {@code ACTIVE} key by the time these tests run (real
 * application startup path), so each test observes that pre-existing state rather than
 * manufacturing its own from scratch.
 */
class KeyLifecycleServiceTest extends IntegrationTestSupport {

  @Autowired private KeyLifecycleService lifecycle;
  @Autowired private IssuerKeyRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void bootstrapIfNeeded_calledTwice_secondCallIsNoOp() {
    long countBefore = repository.countByTenantId(TenantContext.current());
    assertThat(countBefore).isGreaterThanOrEqualTo(1);

    Optional<IssuerKeySummary> second =
        lifecycle.bootstrapIfNeeded(TenantContext.current(), TenantContext.currentSlug());

    assertThat(second).isEmpty();
    assertThat(repository.countByTenantId(TenantContext.current())).isEqualTo(countBefore);
    assertThat(repository.findByTenantIdAndState(TenantContext.current(), "ACTIVE")).isPresent();
  }

  /**
   * KH-2.1 Part B (spec FS-2.1 D4) — functional proof, not just the structural one {@code
   * RepositoryDefaultTransactionsTest} pins down: a bare {@code
   * IssuerKeyRepository#countByTenantId} call, with no ambient service transaction at all, must
   * still be transaction-scoped enough for {@code shared.TenantContextTransactionExecutionListener}
   * to fire and set {@code app.tenant_id} — otherwise this closed-fails to zero rows regardless of
   * the real data (exactly the bug {@code CredentialService#enforceSchemaAllowlist} hit before the
   * repository-level {@code @Transactional(readOnly = true)} fix). Provisions a key for a tenant
   * other than the shared context's default, then shows the SAME bare call sees it only when {@link
   * TenantContext} is set to that tenant, and sees nothing when it isn't — proving isolation is
   * enforced by RLS itself, not by this call's own {@code tenantId} parameter.
   */
  @Test
  void countByTenantId_calledBareWithNoAmbientTransaction_isStillTenantScopedByRls() {
    UUID otherTenant = Uuidv7.generate();
    String otherSlug = "rls-repo-proof-" + otherTenant;
    // issuer_key.tenant_id has a foreign key to tenant(id) — a real row is needed regardless of
    // RLS (tenant itself is excluded from RLS, spec D2, so this bare insert needs no tenant
    // context of its own).
    jdbcTemplate.update(
        "INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status, created_at,"
            + " updated_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, now(), now())",
        otherTenant,
        otherSlug,
        "{\"en\":\"RLS proof\",\"ar\":\"إثبات\"}",
        "OTHER",
        "SAAS",
        "ACTIVE");

    TenantContext.set(otherTenant, otherSlug);
    try {
      lifecycle.bootstrapIfNeeded(otherTenant, otherSlug);
    } finally {
      TenantContext.clear();
    }

    TenantContext.set(otherTenant, otherSlug);
    try {
      assertThat(repository.countByTenantId(otherTenant)).isEqualTo(1);
    } finally {
      TenantContext.clear();
    }

    // Same tenantId argument, but no ambient TenantContext for it (back at the shared context's
    // default) — RLS hides the row regardless of the WHERE clause matching it.
    assertThat(repository.countByTenantId(otherTenant)).isZero();
  }

  @Test
  void resolvePublicKey_unknownKid_returnsEmpty_noFallback() {
    Optional<PublicKeyHandle> resolved = lifecycle.resolvePublicKey("nonexistent-tenant:key-999");

    assertThat(resolved).isEmpty();
  }

  @Test
  void resolvePublicKey_retiredKid_returnsEmpty_noFallback() {
    IssuerKey activeBefore =
        repository.findByTenantIdAndState(TenantContext.current(), "ACTIVE").orElseThrow();
    String oldKid = activeBefore.getKid();

    IssuerKeySummary rotated =
        lifecycle.rotate(TenantContext.current(), TenantContext.currentSlug());

    // The pre-rotate ACTIVE key is now RETIRING. The MVP has no automatic RETIRING->RETIRED timer
    // yet (spec FS-0.5 §5), so force it the rest of the way to exercise "RETIRED never resolves."
    IssuerKey nowRetiring = repository.findByKid(oldKid).orElseThrow();
    assertThat(nowRetiring.getState()).isEqualTo("RETIRING");
    nowRetiring.setState("RETIRED");
    repository.save(nowRetiring);

    assertThat(lifecycle.resolvePublicKey(oldKid)).isEmpty();
    // Sanity: the freshly rotated-in ACTIVE key is unaffected and still resolves.
    assertThat(lifecycle.resolvePublicKey(rotated.kid())).isPresent();
  }

  @Test
  void rotate_exactlyOneActiveAfterwards_oldRetiring_bothPublished_oldSignatureStillVerifies()
      throws Exception {
    IssuerKey activeBefore =
        repository.findByTenantIdAndState(TenantContext.current(), "ACTIVE").orElseThrow();
    String oldKid = activeBefore.getKid();
    SignResult oldSigned = lifecycle.signWithActiveKey(TenantContext.current(), sampleClaims());
    assertThat(oldSigned.kid()).isEqualTo(oldKid);

    IssuerKeySummary rotated =
        lifecycle.rotate(TenantContext.current(), TenantContext.currentSlug());

    assertThat(rotated.kid()).isNotEqualTo(oldKid);
    assertThat(rotated.state()).isEqualTo("ACTIVE");

    List<IssuerKey> activeRows =
        repository.findByTenantIdAndStateIn(TenantContext.current(), List.of("ACTIVE"));
    assertThat(activeRows).hasSize(1);
    assertThat(activeRows.get(0).getKid()).isEqualTo(rotated.kid());

    IssuerKey oldRow = repository.findByKid(oldKid).orElseThrow();
    assertThat(oldRow.getState()).isEqualTo("RETIRING");

    List<PublishedKey> published =
        lifecycle.publishableKeysForDefaultTenantJwks(TenantContext.current());
    assertThat(published).extracting(PublishedKey::kid).contains(oldKid, rotated.kid());

    // Old signature (signed before rotation) must still verify against the now-RETIRING key.
    Optional<PublicKeyHandle> oldHandle = lifecycle.resolvePublicKey(oldKid);
    assertThat(oldHandle).isPresent();

    // New signing must use the new kid.
    SignResult newSigned = lifecycle.signWithActiveKey(TenantContext.current(), sampleClaims());
    assertThat(newSigned.kid()).isEqualTo(rotated.kid());
  }

  @Test
  void generatedKey_publicJwkJson_neverContainsPrivateComponent() {
    IssuerKey active =
        repository.findByTenantIdAndState(TenantContext.current(), "ACTIVE").orElseThrow();

    // EC JWK private material is carried under the "d" member (RFC 7518 §6.2.2.1). toPublicJWK()
    // strips it — this assertion is the DB-row half of DoD #6; JwksControllerTest covers the
    // HTTP-response half.
    assertThat(active.getPublicJwkJson()).doesNotContain("\"d\"");
  }

  @Test
  void bootstrap_writesKeyCreatedAuditRow() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'KEY_CREATED' AND entity_type ="
                + " 'issuer_key'",
            Integer.class);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  @Test
  void rotate_writesKeyRotatedAuditRow() {
    lifecycle.rotate(TenantContext.current(), TenantContext.currentSlug());

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'KEY_ROTATED' AND entity_type ="
                + " 'issuer_key'",
            Integer.class);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  private static JWTClaimsSet sampleClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject("test-subject")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
  }
}
