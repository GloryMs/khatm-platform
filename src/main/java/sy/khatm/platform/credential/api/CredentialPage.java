package sy.khatm.platform.credential.api;

import java.util.List;

/**
 * Page envelope for {@code GET /api/v1/credentials} (KH-1.1.4) — the platform's first paginated
 * list endpoint.
 *
 * @param items this page's rows, sorted by {@code issuedAt} descending
 * @param page zero-based page number this response contains
 * @param size the page size actually applied (after the server-side cap of 100)
 * @param totalElements total rows matching the filters, across every page
 * @param totalPages total number of pages matching the filters, at this page size
 */
public record CredentialPage(
    List<CredentialSummary> items, int page, int size, long totalElements, int totalPages) {}
