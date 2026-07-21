package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.CredentialPage;
import sy.khatm.platform.credential.api.CredentialSummary;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaSummary;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-1.1.4 — {@code CredentialService#search} at the service level: every filter (ref, pseudoRef,
 * schemaId, revoked) applied individually and AND-combined, pagination, and the server-side page
 * size cap.
 */
class CredentialSearchServiceTest extends IntegrationTestSupport {

  @Autowired private CredentialService credentialService;
  @Autowired private SchemaCatalog schemaCatalog;

  @Test
  void search_byRef_returnsExactMatch() {
    IssueResponse issued = issue("SearchByRef/v1", "holder-search-ref");

    CredentialPage page = credentialService.search(issued.ref(), null, null, null, null, null);

    assertThat(page.items()).extracting(CredentialSummary::ref).containsExactly(issued.ref());
    assertThat(page.totalElements()).isEqualTo(1);
  }

  @Test
  void search_byUnknownPseudoRef_returnsEmptyPage() {
    CredentialPage page =
        credentialService.search(
            null, "no-such-holder-" + UUID.randomUUID(), null, null, null, null);

    assertThat(page.items()).isEmpty();
    assertThat(page.totalElements()).isZero();
  }

  @Test
  void search_byPseudoRef_returnsOnlyThatHoldersCredentials() {
    String pseudoRef = "holder-search-pseudo-" + UUID.randomUUID();
    IssueResponse issued = issue("SearchByPseudoRef/v1", pseudoRef);
    issue("SearchByPseudoRefOther/v1", "holder-search-pseudo-other-" + UUID.randomUUID());

    CredentialPage page = credentialService.search(null, pseudoRef, null, null, null, null);

    assertThat(page.items()).extracting(CredentialSummary::ref).containsExactly(issued.ref());
  }

  @Test
  void search_bySchemaId_filtersToThatSchemaOnly() {
    IssueResponse issued = issue("SearchBySchemaId/v1", "holder-search-schema");
    issue("SearchBySchemaIdOther/v1", "holder-search-schema-other");
    UUID schemaId = schemaIdFor("SearchBySchemaId/v1");

    CredentialPage page = credentialService.search(null, null, schemaId, null, null, null);

    assertThat(page.items()).extracting(CredentialSummary::ref).containsExactly(issued.ref());
    assertThat(page.items().get(0).schemaCode()).isEqualTo("SearchBySchemaId/v1");
  }

  @Test
  void search_byRevoked_filtersOutActiveCredentials() {
    IssueResponse issued = issue("SearchByRevoked/v1", "holder-search-revoked");
    credentialService.revoke(UUID.fromString(issued.id()));
    issue("SearchByRevokedOther/v1", "holder-search-revoked-other");

    CredentialPage revokedOnly =
        credentialService.search(null, null, null, Boolean.TRUE, null, null);

    assertThat(revokedOnly.items()).extracting(CredentialSummary::ref).contains(issued.ref());
    assertThat(revokedOnly.items()).allMatch(CredentialSummary::revoked);
  }

  @Test
  void search_pageSizeCappedAtOneHundred() {
    CredentialPage page = credentialService.search(null, null, null, null, 0, 500);

    assertThat(page.size()).isEqualTo(100);
  }

  @Test
  void search_negativePageAndZeroSize_clampToSafeDefaults() {
    CredentialPage page = credentialService.search(null, null, null, null, -5, 0);

    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(1);
  }

  @Test
  void search_sortsByIssuedAtDescending() {
    String pseudoRef = "holder-search-order-" + UUID.randomUUID();
    IssueResponse first = issue("SearchOrderFirst/v1", pseudoRef);
    IssueResponse second = issue("SearchOrderSecond/v1", pseudoRef);

    CredentialPage page = credentialService.search(null, pseudoRef, null, null, 0, 10);

    assertThat(page.items())
        .extracting(CredentialSummary::ref)
        .containsExactly(second.ref(), first.ref());
  }

  private IssueResponse issue(String schemaCode, String holderRef) {
    return credentialService.issue(
        new IssueRequest(schemaCode, holderRef, 1, 60, Map.of("field", "value"), List.of()));
  }

  private UUID schemaIdFor(String code) {
    return schemaCatalog.listAll(null).stream()
        .filter(s -> code.equals(s.code()))
        .map(SchemaSummary::id)
        .findFirst()
        .orElseThrow();
  }
}
