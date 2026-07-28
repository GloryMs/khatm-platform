package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.credential.api.ConsumeRequest;
import sy.khatm.platform.credential.api.CredentialPage;
import sy.khatm.platform.credential.api.CredentialSummary;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaSummary;
import sy.khatm.platform.shared.error.ValidationException;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * chore/credential-search-status-filter — the console's recorded platform ask: server-side {@code
 * status} filtering on {@code CredentialService#search}, matching exactly the same derivation
 * ({@link CredentialStatus#derive}) the row's own {@code status} field uses (see that class's
 * Javadoc for the single-source-of-derivation discipline this test suite pins).
 *
 * <p>An {@code EXPIRED} fixture cannot be issued directly with a negative {@code validMinutes} —
 * {@code credential}'s own {@code CHECK (valid_to > valid_from)} constraint (V1 baseline) rejects
 * an already-inverted window at INSERT time. Every {@code EXPIRED} row here is issued normally,
 * then backdated in place via a direct SQL {@code UPDATE} that moves both {@code valid_from} and
 * {@code valid_to} into the past together (preserving {@code valid_to > valid_from}), landing
 * {@code valid_to} safely behind {@code now()} by the time any assertion runs.
 */
class CredentialSearchStatusFilterTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private SchemaCatalog schemaCatalog;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void search_statusFilter_eachReachableStatus_matchesOnlyItsOwnRow() {
    Fixture f = buildFixture("StatusFilterEach/v1");

    assertOnlyMatches(f.schemaId(), "ACTIVE", f.activeRef());
    assertOnlyMatches(f.schemaId(), "EXHAUSTED", f.exhaustedRef());
    assertOnlyMatches(f.schemaId(), "REVOKED", f.revokedRef());
    assertOnlyMatches(f.schemaId(), "EXPIRED", f.expiredRef());
  }

  @Test
  void search_statusFilter_multipleValues_orTogether() {
    Fixture f = buildFixture("StatusFilterOr/v1");

    CredentialPage page =
        credentialService.search(
            null, null, f.schemaId(), null, List.of("EXHAUSTED", "REVOKED"), null, null);

    assertThat(page.items())
        .extracting(CredentialSummary::ref)
        .containsExactlyInAnyOrder(f.exhaustedRef(), f.revokedRef());
  }

  @Test
  void search_statusFilter_absent_returnsEveryReachableStatus() {
    Fixture f = buildFixture("StatusFilterNone/v1");

    CredentialPage page =
        credentialService.search(null, null, f.schemaId(), null, null, null, null);

    assertThat(page.items())
        .extracting(CredentialSummary::ref)
        .containsExactlyInAnyOrder(f.activeRef(), f.exhaustedRef(), f.revokedRef(), f.expiredRef());
  }

  @Test
  void search_statusFilter_expiredBoundary_justPastIsExpired_justFutureIsActive() {
    IssueResponse justExpired =
        issue("StatusFilterBoundary/v1", "boundary-past-" + UUID.randomUUID());
    backdateWindow(
        UUID.fromString(justExpired.id()),
        "2 minutes",
        "500 milliseconds"); // valid_to = now - 0.5s
    IssueResponse justActive =
        issue("StatusFilterBoundary/v1", "boundary-future-" + UUID.randomUUID());
    nudgeWindowJustIntoTheFuture(UUID.fromString(justActive.id())); // valid_to = now + 2s
    UUID schemaId = schemaIdFor("StatusFilterBoundary/v1");

    CredentialPage expiredPage =
        credentialService.search(null, null, schemaId, null, List.of("EXPIRED"), null, null);
    CredentialPage activePage =
        credentialService.search(null, null, schemaId, null, List.of("ACTIVE"), null, null);

    assertThat(expiredPage.items())
        .extracting(CredentialSummary::ref)
        .containsExactly(justExpired.ref());
    assertThat(activePage.items())
        .extracting(CredentialSummary::ref)
        .containsExactly(justActive.ref());
  }

  @Test
  void search_statusFilter_composesWithPagination() {
    Fixture f = buildFixture("StatusFilterPaging/v1");

    // 4 rows total under this schema (one per reachable status), no status filter, page size 2 —
    // the union of both pages must be exactly the 4 rows regardless of which lands on which page.
    CredentialPage firstPage = credentialService.search(null, null, f.schemaId(), null, null, 0, 2);
    CredentialPage secondPage =
        credentialService.search(null, null, f.schemaId(), null, null, 1, 2);

    assertThat(firstPage.items()).hasSize(2);
    assertThat(secondPage.items()).hasSize(2);
    assertThat(firstPage.totalElements()).isEqualTo(4);
    assertThat(secondPage.totalElements()).isEqualTo(4);
    List<String> allRefs =
        Stream.concat(firstPage.items().stream(), secondPage.items().stream())
            .map(CredentialSummary::ref)
            .toList();
    assertThat(allRefs)
        .containsExactlyInAnyOrder(f.activeRef(), f.exhaustedRef(), f.revokedRef(), f.expiredRef());

    // Status filter + pagination together: paginate within just the OR-selected subset.
    CredentialPage filteredPage =
        credentialService.search(
            null, null, f.schemaId(), null, List.of("ACTIVE", "EXPIRED"), 0, 1);
    assertThat(filteredPage.totalElements()).isEqualTo(2);
    assertThat(filteredPage.items()).hasSize(1);
  }

  @Test
  void search_statusFilter_invalidValue_throwsValidationException() {
    assertThatThrownBy(
            () ->
                credentialService.search(
                    null, null, null, null, List.of("NOT_A_REAL_STATUS"), null, null))
        .isInstanceOf(ValidationException.class);
  }

  /**
   * Single-source-of-derivation regression: for a mixed-status fixture, the id set a status filter
   * returns must exactly equal the id set of rows the SAME unfiltered search call reports as having
   * that status via its own {@code status} field — a row can never show status X while being
   * excluded from filter X, or vice versa.
   */
  @Test
  void search_statusFilter_neverDisagreesWithTheRowsOwnStatusField() {
    Fixture f = buildFixture("StatusFilterConsistency/v1");

    CredentialPage unfiltered =
        credentialService.search(null, null, f.schemaId(), null, null, null, null);
    Map<String, List<String>> refsByDisplayedStatus =
        unfiltered.items().stream()
            .collect(
                Collectors.groupingBy(
                    CredentialSummary::status,
                    Collectors.mapping(CredentialSummary::ref, Collectors.toList())));

    for (String status : List.of("ACTIVE", "EXHAUSTED", "REVOKED", "EXPIRED")) {
      CredentialPage filtered =
          credentialService.search(null, null, f.schemaId(), null, List.of(status), null, null);
      List<String> filteredRefs = filtered.items().stream().map(CredentialSummary::ref).toList();
      assertThat(filteredRefs)
          .as("filter=%s must match exactly the rows whose own status field is %s", status, status)
          .containsExactlyInAnyOrderElementsOf(
              refsByDisplayedStatus.getOrDefault(status, List.of()));
    }
  }

  private void assertOnlyMatches(UUID schemaId, String status, String expectedRef) {
    CredentialPage page =
        credentialService.search(null, null, schemaId, null, List.of(status), null, null);
    assertThat(page.items()).extracting(CredentialSummary::ref).containsExactly(expectedRef);
  }

  private Fixture buildFixture(String schemaCode) {
    IssueResponse active = issue(schemaCode, "active-" + UUID.randomUUID());
    IssueResponse exhausted = issue(schemaCode, "exhausted-" + UUID.randomUUID());
    credentialService.consume(new ConsumeRequest(exhausted.id(), "consumer-x", null));
    IssueResponse revoked = issue(schemaCode, "revoked-" + UUID.randomUUID());
    credentialService.revoke(UUID.fromString(revoked.id()));
    IssueResponse expired = issue(schemaCode, "expired-" + UUID.randomUUID());
    backdateWindow(UUID.fromString(expired.id()), "2 hours", "1 hour"); // valid_to = now - 1h

    return new Fixture(
        schemaIdFor(schemaCode), active.ref(), exhausted.ref(), revoked.ref(), expired.ref());
  }

  private IssueResponse issue(String schemaCode, String holderRef) {
    return credentialService.issue(
        new IssueRequest(schemaCode, holderRef, 1, 60, Map.of("field", "value"), List.of()));
  }

  /**
   * Move both {@code valid_from}/{@code valid_to} into the past together, preserving {@code
   * valid_to > valid_from} (the CHECK constraint an UPDATE is bound by exactly as much as an
   * INSERT), landing {@code valid_to} behind {@code now()} by {@code validToAgo}.
   */
  private void backdateWindow(UUID credentialId, String validFromAgo, String validToAgo) {
    jdbc.update(
        "UPDATE credential SET valid_from = now() - interval '"
            + validFromAgo
            + "', valid_to = now() - interval '"
            + validToAgo
            + "' WHERE id = ?",
        credentialId);
  }

  /** {@code valid_to} just barely in the future — still comfortably ACTIVE, not EXPIRED. */
  private void nudgeWindowJustIntoTheFuture(UUID credentialId) {
    jdbc.update(
        "UPDATE credential SET valid_from = now() - interval '1 minute',"
            + " valid_to = now() + interval '2 seconds' WHERE id = ?",
        credentialId);
  }

  private UUID schemaIdFor(String code) {
    return schemaCatalog.listAll(null).stream()
        .filter(s -> code.equals(s.code()))
        .map(SchemaSummary::id)
        .findFirst()
        .orElseThrow();
  }

  private record Fixture(
      UUID schemaId, String activeRef, String exhaustedRef, String revokedRef, String expiredRef) {}
}
