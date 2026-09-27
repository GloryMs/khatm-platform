package sy.khatm.platform.issuerclient.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.SystemAccessExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * KH-2.8.1 (spec FS-2.7a D10) — the worker sweep revokes each due RETIRING client under that
 * client's OWN tenant context, skips clients whose tenant vanished, and clears the context.
 */
class RetiringSweeperTest {

  private final IssuerClientRepository repository = mock(IssuerClientRepository.class);
  private final IssuerClientLifecycle lifecycle = mock(IssuerClientLifecycle.class);
  private final SystemAccessExecutor systemAccess = mock(SystemAccessExecutor.class);
  private final TenantDirectory tenants = mock(TenantDirectory.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
  private final RetiringSweeper sweeper =
      new RetiringSweeper(repository, lifecycle, systemAccess, tenants, clock);

  @SuppressWarnings("unchecked")
  private void runSupplierInline() {
    when(systemAccess.runAsSystem(any(Supplier.class)))
        .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(0)).get());
  }

  private static IssuerClient client(UUID id, UUID tenantId) {
    IssuerClient c = new IssuerClient();
    c.setId(id);
    c.setTenantId(tenantId);
    c.setNameI18n(new LocalizedText("x", "x"));
    c.setStatus(IssuerClient.STATUS_RETIRING);
    return c;
  }

  @Test
  void sweep_revokesEachDueClientUnderItsOwnTenant_andClearsTheContext() {
    runSupplierInline();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(repository.findRetiringDue(clock.instant()))
        .thenReturn(List.of(client(a, tenantA), client(b, tenantB)));
    when(tenants.findById(tenantA))
        .thenReturn(
            Optional.of(new TenantRef(tenantA, "a", "ACTIVE", new LocalizedText("a", "a"))));
    when(tenants.findById(tenantB))
        .thenReturn(
            Optional.of(new TenantRef(tenantB, "b", "ACTIVE", new LocalizedText("b", "b"))));
    AtomicReference<String> slugSeenForA = new AtomicReference<>();
    AtomicReference<String> slugSeenForB = new AtomicReference<>();
    when(lifecycle.finishRetirement(a))
        .thenAnswer(
            invocation -> {
              slugSeenForA.set(TenantContext.currentSlug());
              return true;
            });
    when(lifecycle.finishRetirement(b))
        .thenAnswer(
            invocation -> {
              slugSeenForB.set(TenantContext.currentSlug());
              return false; // already handled elsewhere — not counted
            });

    int revoked = sweeper.sweep();

    assertThat(revoked).isEqualTo(1);
    assertThat(slugSeenForA.get()).isEqualTo("a");
    assertThat(slugSeenForB.get()).isEqualTo("b");
    assertThat(TenantContext.currentSlug())
        .as("the thread-local is cleared afterwards")
        .isEqualTo(TenantContext.DEFAULT_TENANT_SLUG);
  }

  @Test
  void sweep_skipsAClientWhoseTenantNoLongerExists() {
    runSupplierInline();
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(repository.findRetiringDue(clock.instant())).thenReturn(List.of(client(id, tenant)));
    when(tenants.findById(tenant)).thenReturn(Optional.empty());

    assertThat(sweeper.sweep()).isZero();
    org.mockito.Mockito.verifyNoInteractions(lifecycle);
  }

  @Test
  void sweep_withNothingDue_doesNothing() {
    runSupplierInline();
    when(repository.findRetiringDue(clock.instant())).thenReturn(List.of());

    assertThat(sweeper.sweep()).isZero();
    verify(repository).findRetiringDue(clock.instant());
  }
}
