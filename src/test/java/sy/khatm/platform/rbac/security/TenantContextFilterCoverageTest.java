package sy.khatm.platform.rbac.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * KH-2.1 review follow-up — structural proof that {@link TenantContextFilter} covers every
 * authenticated route, not an assumption. {@code SecurityConfig} wires exactly two {@link
 * SecurityFilterChain}s (spec FS-0.6b §3: every request matches one or the other, there is no
 * third), and {@link TenantContextFilter} is added to both via {@code addFilterAfter} with no
 * {@code RequestMatcher}/path scoping at all — so once it is present in a chain's filter list at
 * all, {@code OncePerRequestFilter} guarantees it runs for every request that chain handles,
 * regardless of which route. That makes chain-level introspection ({@link
 * SecurityFilterChain#getFilters()}, public API) a complete proof, not a sample: a newly added
 * route needs no per-route entry in this test (or anywhere else) to be covered, because it can only
 * ever be reached through one of these same two chains.
 *
 * <p>An alternative — a {@code MockMvc} sweep hitting every secured route from {@code
 * SecurityConfig}'s {@code configureAuthorization} with a real principal — was considered and
 * rejected: it would need to enumerate and duplicate that route list (all of which are already
 * {@code private} constants inside {@code SecurityConfig}, by design — see its own Javadoc), get
 * silently stale the moment a new route is added without a matching test entry, and would only
 * prove the routes it happened to list, not the structural guarantee this test proves once and for
 * all routes, present and future, on either chain.
 *
 * <p>Lives in this package (not {@code rbac}) specifically to reach {@link TenantContextFilter} and
 * {@link ApiKeyAuthFilter}, both package-private by design (Modulith module-privacy, CONVENTIONS
 * §5's entity-visibility rationale applies identically to these).
 */
class TenantContextFilterCoverageTest extends IntegrationTestSupport {

  @Autowired
  @Qualifier("apiKeySecurityFilterChain")
  private SecurityFilterChain apiKeyChain;

  @Autowired
  @Qualifier("sessionSecurityFilterChain")
  private SecurityFilterChain sessionChain;

  @Test
  void apiKeyChain_runsTenantContextFilter_afterApiKeyAuthResolvesThePrincipal() {
    List<Filter> filters = apiKeyChain.getFilters();
    int apiKeyAuthIndex = indexOfType(filters, ApiKeyAuthFilter.class);
    int tenantContextIndex = indexOfType(filters, TenantContextFilter.class);

    assertThat(apiKeyAuthIndex).as("ApiKeyAuthFilter must be present").isGreaterThanOrEqualTo(0);
    assertThat(tenantContextIndex)
        .as("TenantContextFilter must be present")
        .isGreaterThanOrEqualTo(0);
    assertThat(tenantContextIndex)
        .as("TenantContextFilter must run after the principal is resolved")
        .isGreaterThan(apiKeyAuthIndex);
  }

  @Test
  void sessionChain_runsTenantContextFilter_afterSessionRestoresThePrincipal() {
    List<Filter> filters = sessionChain.getFilters();
    int securityContextHolderIndex = indexOfType(filters, SecurityContextHolderFilter.class);
    int tenantContextIndex = indexOfType(filters, TenantContextFilter.class);

    assertThat(securityContextHolderIndex)
        .as("SecurityContextHolderFilter must be present")
        .isGreaterThanOrEqualTo(0);
    assertThat(tenantContextIndex)
        .as("TenantContextFilter must be present")
        .isGreaterThanOrEqualTo(0);
    assertThat(tenantContextIndex)
        .as("TenantContextFilter must run after session-restored authentication is available")
        .isGreaterThan(securityContextHolderIndex);
  }

  /**
   * KH-2.2b — {@link PasswordChangeEnforcementFilter} needs {@code shared.TenantContext} already
   * resolved (its live {@code app_user} read is RLS-scoped to the user's own tenant), so it must
   * run after {@link TenantContextFilter} on the session chain. It has no place on the api-key
   * chain at all — API keys carry no human password and are unaffected by the forced-change gate.
   */
  @Test
  void sessionChain_runsPasswordChangeEnforcementFilter_afterTenantContextFilter() {
    List<Filter> filters = sessionChain.getFilters();
    int tenantContextIndex = indexOfType(filters, TenantContextFilter.class);
    int passwordChangeIndex = indexOfType(filters, PasswordChangeEnforcementFilter.class);

    assertThat(passwordChangeIndex)
        .as("PasswordChangeEnforcementFilter must be present on the session chain")
        .isGreaterThanOrEqualTo(0);
    assertThat(passwordChangeIndex)
        .as("PasswordChangeEnforcementFilter must run after TenantContextFilter")
        .isGreaterThan(tenantContextIndex);
  }

  @Test
  void apiKeyChain_hasNoPasswordChangeEnforcementFilter() {
    List<Filter> filters = apiKeyChain.getFilters();
    assertThat(indexOfType(filters, PasswordChangeEnforcementFilter.class))
        .as("the api-key chain must not run the password-change gate at all")
        .isEqualTo(-1);
  }

  private static int indexOfType(List<Filter> filters, Class<?> type) {
    for (int i = 0; i < filters.size(); i++) {
      if (type.isInstance(filters.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
