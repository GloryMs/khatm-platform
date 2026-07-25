package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.rbac.api.ApiKeyOwnerLookup;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef.OwnerKind;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Spec FS-1.5.4 "also needed", KH-1.1.5-BE — {@link ConsumingPartyStatsService} aggregates multiple
 * {@code api_key} rows owned by the same party into one entry, and excludes {@code TENANT}-owned
 * key activity entirely (no party to attribute it to).
 */
@ExtendWith(MockitoExtension.class)
class ConsumingPartyStatsServiceTest {

  private static final UUID PARTY_ID = UUID.randomUUID();
  private static final UUID KEY_1 = UUID.randomUUID();
  private static final UUID KEY_2 = UUID.randomUUID();
  private static final UUID TENANT_KEY = UUID.randomUUID();

  @Mock private AuditService audit;
  @Mock private ApiKeyOwnerLookup ownerLookup;
  @Mock private ConsumingPartyAdmin consumingParties;

  private ConsumingPartyStatsService service;

  @BeforeEach
  void setUp() {
    service = new ConsumingPartyStatsService(audit, ownerLookup, consumingParties);
  }

  @Test
  void statsForWindow_sumsMultipleKeysOwnedBySameParty() {
    Map<UUID, Map<String, Long>> byActor =
        Map.of(
            KEY_1, Map.of("CREDENTIAL_CONSUMED", 3L, "CONSUME_SCHEMA_DENIED", 1L),
            KEY_2, Map.of("CREDENTIAL_CONSUMED", 2L));
    when(audit.actorActionCounts(any(), any(), any())).thenReturn(byActor);
    when(ownerLookup.resolveOwners(any()))
        .thenReturn(
            Map.of(
                KEY_1, new ApiKeyOwnerRef(OwnerKind.CONSUMING_PARTY, PARTY_ID),
                KEY_2, new ApiKeyOwnerRef(OwnerKind.CONSUMING_PARTY, PARTY_ID)));
    ConsumingPartyView party =
        new ConsumingPartyView(
            PARTY_ID,
            "acme",
            new LocalizedText("Acme", "أكمي"),
            "ACTIVE",
            Instant.now(),
            List.of());
    when(consumingParties.list()).thenReturn(List.of(party));

    List<ConsumingPartyStatsView> views = service.statsForWindow(Instant.now(), Instant.now());

    assertThat(views).hasSize(1);
    ConsumingPartyStatsView view = views.get(0);
    assertThat(view.consumed()).isEqualTo(5L);
    assertThat(view.denied()).isEqualTo(1L);
    assertThat(view.successRate())
        .isCloseTo(5.0 / 6.0, org.assertj.core.data.Offset.offset(0.0001));
  }

  @Test
  void statsForWindow_excludesTenantOwnedKeyActivity() {
    Map<UUID, Map<String, Long>> byActor = Map.of(TENANT_KEY, Map.of("CREDENTIAL_CONSUMED", 7L));
    when(audit.actorActionCounts(any(), any(), any())).thenReturn(byActor);
    when(ownerLookup.resolveOwners(any()))
        .thenReturn(Map.of(TENANT_KEY, new ApiKeyOwnerRef(OwnerKind.TENANT, null)));

    List<ConsumingPartyStatsView> views = service.statsForWindow(Instant.now(), Instant.now());

    assertThat(views).isEmpty();
  }

  @Test
  void statsForWindow_noActivity_returnsEmptyList_noPartyListCall() {
    when(audit.actorActionCounts(any(), any(), any())).thenReturn(Map.of());

    List<ConsumingPartyStatsView> views = service.statsForWindow(Instant.now(), Instant.now());

    assertThat(views).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(consumingParties);
  }
}
