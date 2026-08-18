package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.api.VerifyResponse;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.support.IntegrationTestSupport;
import sy.khatm.platform.tenant.api.TenantAdmin;
import sy.khatm.platform.tenant.api.TenantView;

/**
 * KH-2.6a, spec FS-2.5 §5/§7 — {@code /verify}'s {@code issuerLineage} field: the full ancestor
 * chain for a child tenant's credential, nearest first, and a clean empty list for a root tenant's
 * — display-only (spec §1), never affecting the actual verification outcome computed by every other
 * test in this package.
 */
class TenantHierarchyLineageVerifyTest extends IntegrationTestSupport {

  @Autowired private TenantAdmin admin;
  @Autowired private CredentialService credentialService;
  @Autowired private SystemAccessExecutor systemAccess;

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private String issueAndVerify(UUID tenantId, String tenantSlug) {
    TenantContext.set(tenantId, tenantSlug);
    IssueResponse issued;
    try {
      issued =
          credentialService.issue(
              new IssueRequest(
                  "GenericDocument/v1", "lineage-holder", 1, 60, Map.of(), null, null));
    } finally {
      TenantContext.clear();
    }
    return issued.sdJwt();
  }

  @Test
  void verify_childTenantsCredential_carriesFullAncestorChain_nearestFirst() {
    TenantView grandparent =
        admin.create(
            uniqueSlug("lineage-gp"), new LocalizedText("Ministry", "الوزارة"), "GOVERNMENT", null);
    TenantView parent =
        admin.create(
            uniqueSlug("lineage-p"),
            new LocalizedText("Department", "الإدارة"),
            "GOVERNMENT",
            null);
    TenantView child =
        admin.create(
            uniqueSlug("lineage-c"), new LocalizedText("Branch", "الفرع"), "GOVERNMENT", null);
    admin.setParent(parent.id(), grandparent.slug());
    admin.setParent(child.id(), parent.slug());

    String sdJwt = issueAndVerify(child.id(), child.slug());
    // Mirrors CredentialController#verify's own wrapping (spec FS-2.1 D5): issuer_key is
    // RLS-protected, and /verify is genuinely anonymous — a presented credential may belong to any
    // tenant, so this lookup runs under system access rather than any one tenant's own context.
    VerifyResponse result = systemAccess.runAsSystem(() -> credentialService.verify(sdJwt));

    assertThat(result.valid()).isTrue();
    assertThat(result.issuerLineage()).isNotNull();
    assertThat(result.issuerLineage()).hasSize(2);
    assertThat(result.issuerLineage().get(0).slug()).isEqualTo(parent.slug());
    assertThat(result.issuerLineage().get(0).nameI18n().en()).isEqualTo("Department");
    assertThat(result.issuerLineage().get(1).slug()).isEqualTo(grandparent.slug());
    assertThat(result.issuerLineage().get(1).nameI18n().en()).isEqualTo("Ministry");
  }

  @Test
  void verify_rootTenantsCredential_carriesEmptyLineage_notNull() {
    TenantView root =
        admin.create(uniqueSlug("lineage-root"), new LocalizedText("Root", "جذر"), "OTHER", null);

    String sdJwt = issueAndVerify(root.id(), root.slug());
    VerifyResponse result = systemAccess.runAsSystem(() -> credentialService.verify(sdJwt));

    assertThat(result.valid()).isTrue();
    assertThat(result.issuerLineage()).isNotNull();
    assertThat(result.issuerLineage()).isEmpty();
  }
}
