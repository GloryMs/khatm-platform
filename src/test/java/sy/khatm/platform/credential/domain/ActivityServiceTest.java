package sy.khatm.platform.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.rbac.api.ApiKeyOwnerLookup;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef.OwnerKind;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.audit.AuditEventView;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Spec FS-1.5.4 #2 (D2/D3/D4), KH-1.1.5-BE — {@link ActivityService} resolves entity refs and
 * consuming-party attribution for the activity feed; a plain Mockito unit test since the logic is
 * pure composition over its collaborators (same stance as {@code key.web.JwksControllerTest}).
 */
@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

  private static final UUID CREDENTIAL_ID = UUID.randomUUID();
  private static final UUID API_KEY_ID = UUID.randomUUID();
  private static final UUID PARTY_ID = UUID.randomUUID();

  @Mock private AuditService audit;
  @Mock private CredentialRepository credentials;
  @Mock private ApiKeyOwnerLookup ownerLookup;
  @Mock private ConsumingPartyAdmin consumingParties;

  private ActivityService service;

  @BeforeEach
  void setUp() {
    service = new ActivityService(audit, credentials, ownerLookup, consumingParties);
  }

  @Test
  void recent_credentialConsumed_resolvesBareIdToRef_andAttributesConsumingParty() {
    AuditEventView row =
        new AuditEventView(
            "CREDENTIAL_CONSUMED",
            "API_KEY",
            API_KEY_ID,
            CREDENTIAL_ID.toString(),
            null,
            Instant.now());
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of(row));

    Credential credential = new Credential();
    credential.setId(CREDENTIAL_ID);
    credential.setRef("CRE-2026-000001");
    when(credentials.findAllById(any())).thenReturn(List.of(credential));

    when(ownerLookup.resolveOwners(any()))
        .thenReturn(Map.of(API_KEY_ID, new ApiKeyOwnerRef(OwnerKind.CONSUMING_PARTY, PARTY_ID)));

    ConsumingPartyView party =
        new ConsumingPartyView(
            PARTY_ID,
            "acme",
            new LocalizedText("Acme", "أكمي"),
            "ACTIVE",
            Instant.now(),
            List.of());
    when(consumingParties.list()).thenReturn(List.of(party));

    List<ActivityEventView> views = service.recent(20, List.of());

    assertThat(views).hasSize(1);
    ActivityEventView view = views.get(0);
    assertThat(view.entityRef()).isEqualTo("CRE-2026-000001");
    assertThat(view.consumingPartyCode()).isEqualTo("acme");
    assertThat(view.consumingPartyName().en()).isEqualTo("Acme");
  }

  @Test
  void recent_credentialIssued_leavesEntityRefUnchanged_noPartyAttribution() {
    AuditEventView row =
        new AuditEventView(
            "CREDENTIAL_ISSUED", "USER", UUID.randomUUID(), "CRE-2026-000002", null, Instant.now());
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of(row));
    when(consumingParties.list()).thenReturn(List.of());

    List<ActivityEventView> views = service.recent(20, List.of());

    assertThat(views).hasSize(1);
    assertThat(views.get(0).entityRef()).isEqualTo("CRE-2026-000002");
    assertThat(views.get(0).consumingPartyCode()).isNull();
    assertThat(views.get(0).consumingPartyName()).isNull();
  }

  @Test
  void recent_consumeSchemaDenied_attributesPartyFromDetail_noOwnerLookupNeeded() {
    AuditEventView row =
        new AuditEventView(
            "CONSUME_SCHEMA_DENIED",
            "API_KEY",
            API_KEY_ID,
            "CRE-2026-000003",
            Map.of("schemaId", UUID.randomUUID().toString(), "party", PARTY_ID.toString()),
            Instant.now());
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of(row));

    ConsumingPartyView party =
        new ConsumingPartyView(
            PARTY_ID,
            "acme",
            new LocalizedText("Acme", "أكمي"),
            "ACTIVE",
            Instant.now(),
            List.of());
    when(consumingParties.list()).thenReturn(List.of(party));

    List<ActivityEventView> views = service.recent(20, List.of());

    assertThat(views.get(0).consumingPartyCode()).isEqualTo("acme");
  }

  @Test
  void recent_unresolvableCredentialRef_fallsBackToRawId() {
    AuditEventView row =
        new AuditEventView(
            "CREDENTIAL_REVOKED",
            "USER",
            UUID.randomUUID(),
            CREDENTIAL_ID.toString(),
            null,
            Instant.now());
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of(row));
    when(credentials.findAllById(any())).thenReturn(List.of());
    when(consumingParties.list()).thenReturn(List.of());

    List<ActivityEventView> views = service.recent(20, List.of());

    assertThat(views.get(0).entityRef()).isEqualTo(CREDENTIAL_ID.toString());
  }

  @Test
  void recent_actionFilter_dropsActionsOutsideEligibleSet() {
    when(audit.recentEvents(anyInt(), any())).thenReturn(List.of());
    when(consumingParties.list()).thenReturn(List.of());

    service.recent(20, List.of("AUTH_LOGIN_SUCCESS", "CREDENTIAL_ISSUED"));

    org.mockito.Mockito.verify(audit).recentEvents(20, List.of("CREDENTIAL_ISSUED"));
  }
}
