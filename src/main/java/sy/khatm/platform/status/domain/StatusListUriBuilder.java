package sy.khatm.platform.status.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sy.khatm.platform.shared.TenantContext;

/**
 * Builds the fully-qualified public status-list URL (spec FS-1.3 D2/D7): {@code
 * {khatm.platform.base-url}/sl/{tenantSlug}/{listCode}}.
 *
 * <p>Shared by {@code StatusListRevokerService} and {@code StatusListLookupService} so the URL
 * shape exists in exactly one place. This class is module-private.
 */
@Component
class StatusListUriBuilder {

  private final String baseUrl;

  StatusListUriBuilder(@Value("${khatm.platform.base-url}") String baseUrl) {
    // Trim a trailing slash so a misconfigured "http://host:8080/" doesn't produce "//sl/...".
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  String build(String listCode) {
    return baseUrl + "/sl/" + TenantContext.currentSlug() + "/" + listCode;
  }
}
