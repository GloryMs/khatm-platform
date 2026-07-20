package sy.khatm.platform.status.api;

/**
 * A resolvable reference to a status list, as seen from outside the {@code status} module.
 *
 * <p>{@code uri} is the fully-qualified {@code GET /sl/{tenantSlug}/{listCode}} URL (spec FS-1.3
 * D2) — callers never need to know the URL shape or the list's own {@code list_code}; that stays an
 * internal detail of the {@code status} module. {@code version} is the list's <em>live</em> {@code
 * status_list.version} at the moment of the call, not necessarily the version of the last published
 * artifact — publication is asynchronous (D3/D5), so a caller comparing this value against the
 * {@code ver} claim of the artifact fetched from {@code uri} may briefly see the artifact lag
 * behind by up to NFR-06's window.
 *
 * @param version the status list's current version
 * @param uri the fully-qualified public status-list URL
 */
public record StatusListRef(long version, String uri) {}
