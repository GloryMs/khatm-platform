package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.audit.AuditEventView;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Spec FS-1.5.4 #3 (D6), KH-1.1.5-BE — {@link AttentionService}'s two shipped item types
 * (schema-denied, verify-failure-rate); a plain Mockito unit test, same stance as {@link
 * ActivityServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class AttentionServiceTest {

  private static final Duration WINDOW_24H = Duration.ofHours(24);
  private static final int CAP = 20;
  private static final Duration VERIFY_WINDOW_1H = Duration.ofHours(1);
  private static final double MULTIPLIER_3X = 3.0;
  private static final long MIN_VOLUME_5 = 5L;

  @Mock private AuditService audit;
  @Mock private SchemaCatalog schemas;
  @Mock private ConsumingPartyAdmin consumingParties;

  private AttentionService newService() {
    return new AttentionService(
        audit,
        schemas,
        consumingParties,
        WINDOW_24H,
        CAP,
        VERIFY_WINDOW_1H,
        MULTIPLIER_3X,
        MIN_VOLUME_5);
  }

  @Test
  void attention_schemaDenied_itemizesRecentRows_withResolvedSchemaAndParty() {
    UUID schemaId = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    AuditEventView row =
        new AuditEventView(
            "CONSUME_SCHEMA_DENIED",
            "API_KEY",
            UUID.randomUUID(),
            "CRE-2026-000001",
            Map.of("schemaId", schemaId.toString(), "party", partyId.toString()),
            Instant.now());
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of(row));
    when(schemas.findById(schemaId))
        .thenReturn(
            java.util.Optional.of(
                new SchemaRef(schemaId, "CriminalRecordExtract", 1, null, null, List.of())));
    ConsumingPartyView party =
        new ConsumingPartyView(
            partyId, "acme", new LocalizedText("Acme", "أكمي"), "ACTIVE", Instant.now(), List.of());
    when(consumingParties.list()).thenReturn(List.of(party));
    when(audit.countActionsInWindow(any(), any())).thenReturn(Map.of());

    List<AttentionItem> items = newService().attention();

    AttentionItem item =
        items.stream()
            .filter(i -> AttentionItem.TYPE_SCHEMA_DENIED.equals(i.type()))
            .findFirst()
            .orElseThrow();
    assertThat(item.detail()).containsEntry("credentialRef", "CRE-2026-000001");
    assertThat(item.detail()).containsEntry("schemaCode", "CriminalRecordExtract");
    assertThat(item.detail()).containsEntry("partyCode", "acme");
  }

  @Test
  void attention_schemaDenied_excludesRowsOutsideWindow() {
    AuditEventView staleRow =
        new AuditEventView(
            "CONSUME_SCHEMA_DENIED",
            "API_KEY",
            UUID.randomUUID(),
            "CRE-old",
            null,
            Instant.now().minus(48, java.time.temporal.ChronoUnit.HOURS));
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of(staleRow));
    when(consumingParties.list()).thenReturn(List.of());
    when(audit.countActionsInWindow(any(), any())).thenReturn(Map.of());

    List<AttentionItem> items = newService().attention();

    assertThat(items).noneMatch(i -> AttentionItem.TYPE_SCHEMA_DENIED.equals(i.type()));
  }

  @Test
  void attention_verifyFailureRate_flaggedWhenRateClearsMultiplier_andVolumeFloorMet() {
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of());
    when(consumingParties.list()).thenReturn(List.of());
    // Current window: 10 total, 6 failed -> 60% failure rate, volume >= 5.
    Map<String, Long> current = Map.of("CREDENTIAL_VERIFY_OK", 4L, "CREDENTIAL_VERIFY_FAILED", 6L);
    // Baseline window: 10 total, 1 failed -> 10% failure rate. 60% >= 3x10% (30%) -> flagged.
    Map<String, Long> baseline = Map.of("CREDENTIAL_VERIFY_OK", 9L, "CREDENTIAL_VERIFY_FAILED", 1L);
    when(audit.countActionsInWindow(any(), any())).thenReturn(current).thenReturn(baseline);

    List<AttentionItem> items = newService().attention();

    AttentionItem item =
        items.stream()
            .filter(i -> AttentionItem.TYPE_VERIFY_FAILURE_RATE.equals(i.type()))
            .findFirst()
            .orElseThrow();
    assertThat(item.detail().get("currentFailed")).isEqualTo(6L);
    assertThat(item.detail().get("baselineTotal")).isEqualTo(10L);
  }

  @Test
  void attention_verifyFailureRate_notFlaggedBelowVolumeFloor() {
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of());
    when(consumingParties.list()).thenReturn(List.of());
    // Only 2 total attempts (below the 5-attempt floor), even though all failed.
    Map<String, Long> current = Map.of("CREDENTIAL_VERIFY_FAILED", 2L);
    when(audit.countActionsInWindow(any(), any())).thenReturn(current).thenReturn(Map.of());

    List<AttentionItem> items = newService().attention();

    assertThat(items).noneMatch(i -> AttentionItem.TYPE_VERIFY_FAILURE_RATE.equals(i.type()));
  }

  @Test
  void attention_verifyFailureRate_notFlaggedWhenRateDoesNotClearMultiplier() {
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of());
    when(consumingParties.list()).thenReturn(List.of());
    // Current: 10 total, 2 failed -> 20% failure rate.
    Map<String, Long> current = Map.of("CREDENTIAL_VERIFY_OK", 8L, "CREDENTIAL_VERIFY_FAILED", 2L);
    // Baseline: 10 total, 1 failed -> 10% -> 3x = 30%; current 20% < 30% -> not flagged.
    Map<String, Long> baseline = Map.of("CREDENTIAL_VERIFY_OK", 9L, "CREDENTIAL_VERIFY_FAILED", 1L);
    when(audit.countActionsInWindow(any(), any())).thenReturn(current).thenReturn(baseline);

    List<AttentionItem> items = newService().attention();

    assertThat(items).noneMatch(i -> AttentionItem.TYPE_VERIFY_FAILURE_RATE.equals(i.type()));
  }

  @Test
  void attention_verifyFailureRate_noFailuresAtAll_neverFlagged() {
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of());
    when(consumingParties.list()).thenReturn(List.of());
    Map<String, Long> current = Map.of("CREDENTIAL_VERIFY_OK", 10L);
    when(audit.countActionsInWindow(any(), any())).thenReturn(current).thenReturn(Map.of());

    List<AttentionItem> items = newService().attention();

    assertThat(items).noneMatch(i -> AttentionItem.TYPE_VERIFY_FAILURE_RATE.equals(i.type()));
  }
}
