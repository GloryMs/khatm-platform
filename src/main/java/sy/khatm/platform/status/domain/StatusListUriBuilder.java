package sy.khatm.platform.status.domain;

import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.PublicUrlBuilder;
import sy.khatm.platform.shared.TenantContext;

/**
 * Builds the fully-qualified public status-list URL (spec FS-1.3 D2/D7): {@code
 * {khatm.public-base-url}/sl/{tenantSlug}/{listCode}}.
 *
 * <p>Shared by {@code StatusListRevokerService} and {@code StatusListLookupService} so the URL
 * shape exists in exactly one place. Delegates the base-URL resolution itself to {@link
 * PublicUrlBuilder} — this class only owns the {@code /sl/...} path shape. Module-private.
 */
@Component
class StatusListUriBuilder {

  private final PublicUrlBuilder publicUrlBuilder;

  StatusListUriBuilder(PublicUrlBuilder publicUrlBuilder) {
    this.publicUrlBuilder = publicUrlBuilder;
  }

  String build(String listCode) {
    return publicUrlBuilder.build("/sl/" + TenantContext.currentSlug() + "/" + listCode);
  }
}
