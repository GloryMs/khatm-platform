package sy.khatm.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * KH-2.1 review follow-up — {@link TenantContext}'s fail-fast guard: an authenticated,
 * non-anonymous principal on the {@link SecurityContextHolder} with no tenant ever set on this
 * thread must throw, never silently return the default tenant. No Spring context needed — the guard
 * is a plain static check against {@link SecurityContextHolder}, deliberately kept dependency-free
 * so it stays fast and cannot itself be the thing that breaks.
 */
class TenantContextFailFastGuardTest {

  @AfterEach
  void cleanup() {
    // Both TenantContext.set and SecurityContextHolder are plain ThreadLocals shared with every
    // other test that happens to run on this thread — never leave either populated.
    TenantContext.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void current_authenticatedNonAnonymousPrincipal_contextNeverSet_throws() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                "some-real-principal", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    assertThatThrownBy(TenantContext::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TenantContextFilter");
  }

  @Test
  void currentSlug_authenticatedNonAnonymousPrincipal_contextNeverSet_throws() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                "some-real-principal", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    assertThatThrownBy(TenantContext::currentSlug)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TenantContextFilter");
  }

  @Test
  void current_anonymousAuthentication_contextUnset_fallsBackToDefault_neverThrows() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANON"))));

    assertThat(TenantContext.current()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    assertThat(TenantContext.currentSlug()).isEqualTo(TenantContext.DEFAULT_TENANT_SLUG);
  }

  @Test
  void current_noAuthenticationAtAll_contextUnset_fallsBackToDefault_neverThrows() {
    // The worker/seeder/most-tests shape: no SecurityContext populated at all.
    assertThat(TenantContext.current()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    assertThat(TenantContext.currentSlug()).isEqualTo(TenantContext.DEFAULT_TENANT_SLUG);
  }

  @Test
  void current_authenticatedNonAnonymousPrincipal_contextExplicitlySet_returnsIt_neverThrows() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                "some-real-principal", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    UUID tenantId = Uuidv7.generate();
    TenantContext.set(tenantId, "some-tenant");

    assertThat(TenantContext.current()).isEqualTo(tenantId);
    assertThat(TenantContext.currentSlug()).isEqualTo("some-tenant");
  }
}
