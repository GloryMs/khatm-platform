package sy.khatm.platform.credential.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.credential.persistence.CredentialRepository;
import sy.khatm.platform.rbac.api.ApiKeyOwnerLookup;
import sy.khatm.platform.rbac.api.ApiKeyOwnerRef;
import sy.khatm.platform.shared.audit.AuditEventView;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Backs {@code GET /api/v1/activity} (spec FS-1.5.4 #2, KH-1.1.5-BE) — reads recent {@code
 * audit_log} rows via {@code shared :: audit} and resolves both open design points the brief
 * flagged before this endpoint could exist:
 *
 * <ul>
 *   <li><b>Spec D3 (entity ref):</b> {@code CREDENTIAL_CONSUMED}/{@code CREDENTIAL_REVOKED} store a
 *       bare credential id, not a {@code ref} — resolved here via {@link CredentialRepository},
 *       this module's own repository, no cross-module call needed.
 *   <li><b>Spec D2/D4 (consuming-party attribution):</b> {@code CREDENTIAL_CONSUMED}'s only link to
 *       "who consumed it" is {@code actor_id} (an {@code api_key.id}) — resolved to the owning
 *       {@code consuming_party} via {@code rbac :: api}'s new {@link ApiKeyOwnerLookup} (spec D2),
 *       then to a display name via {@code consumer :: api}'s {@link ConsumingPartyAdmin#list()}
 *       (spec D4 — reused as-is, no new batch-get method). {@code CONSUME_SCHEMA_DENIED} already
 *       carries {@code detail.party} directly (the brief's one exception) — resolved through the
 *       same party-name map, no {@code ApiKeyOwnerLookup} call needed for that action.
 * </ul>
 *
 * <p>Scoped deliberately to the credential-lifecycle-relevant actions only (spec D1b) — not a
 * general audit-trail viewer across every module's events.
 *
 * <p>{@code public} Java visibility only for cross-package access from {@code credential.web}
 * within this same module — Modulith module privacy is enforced by package structure and {@code
 * ModulithBoundariesTest}, not Java visibility (see {@code CredentialService}'s identical stance).
 */
@Service
public class ActivityService {

  /**
   * The action set this feed ever surfaces (spec D1b) — deliberately narrower than the full {@code
   * AuditAction} catalog; {@code AUTH_*}/{@code KEY_*}/{@code CONSUMING_PARTY_*} events stay out of
   * scope for this endpoint.
   */
  public static final List<String> ELIGIBLE_ACTIONS =
      List.of(
          "CREDENTIAL_ISSUED",
          "CREDENTIAL_CONSUMED",
          "CREDENTIAL_REVOKED",
          "CONSUME_SCHEMA_DENIED",
          "CLAIM_CODE_REDEEMED",
          "CREDENTIAL_VERIFY_OK",
          "CREDENTIAL_VERIFY_FAILED");

  private static final Set<String> ID_NOT_REF_ACTIONS =
      Set.of("CREDENTIAL_CONSUMED", "CREDENTIAL_REVOKED");
  private static final String ACTOR_TYPE_API_KEY = "API_KEY";

  private final AuditService audit;
  private final CredentialRepository credentials;
  private final ApiKeyOwnerLookup ownerLookup;
  private final ConsumingPartyAdmin consumingParties;

  ActivityService(
      AuditService audit,
      CredentialRepository credentials,
      ApiKeyOwnerLookup ownerLookup,
      ConsumingPartyAdmin consumingParties) {
    this.audit = audit;
    this.credentials = credentials;
    this.ownerLookup = ownerLookup;
    this.consumingParties = consumingParties;
  }

  /**
   * The most recent activity rows, resolved for display.
   *
   * @param limit the maximum number of rows to return
   * @param requestedActions an optional subset of {@link #ELIGIBLE_ACTIONS} to filter to; {@code
   *     null}/empty means every eligible action
   */
  public List<ActivityEventView> recent(int limit, List<String> requestedActions) {
    List<String> actionFilter = resolveActionFilter(requestedActions);
    List<AuditEventView> rows = audit.recentEvents(limit, actionFilter);

    Map<UUID, String> refsById = resolveCredentialRefs(rows);
    Map<UUID, ConsumingPartyView> partiesById = indexParties();
    Map<UUID, ApiKeyOwnerRef> owners = resolveApiKeyOwners(rows);

    return rows.stream().map(row -> toView(row, refsById, owners, partiesById)).toList();
  }

  private static List<String> resolveActionFilter(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return ELIGIBLE_ACTIONS;
    }
    return requested.stream().filter(ELIGIBLE_ACTIONS::contains).toList();
  }

  private Map<UUID, String> resolveCredentialRefs(List<AuditEventView> rows) {
    Set<UUID> ids = new HashSet<>();
    for (AuditEventView row : rows) {
      if (ID_NOT_REF_ACTIONS.contains(row.action())) {
        parseUuid(row.entityRef()).ifPresent(ids::add);
      }
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> refs = new HashMap<>();
    credentials.findAllById(ids).forEach(c -> refs.put(c.getId(), c.getRef()));
    return refs;
  }

  private Map<UUID, ApiKeyOwnerRef> resolveApiKeyOwners(List<AuditEventView> rows) {
    Set<UUID> apiKeyActorIds = new HashSet<>();
    for (AuditEventView row : rows) {
      if (ACTOR_TYPE_API_KEY.equals(row.actorType()) && row.actorId() != null) {
        apiKeyActorIds.add(row.actorId());
      }
    }
    return apiKeyActorIds.isEmpty() ? Map.of() : ownerLookup.resolveOwners(apiKeyActorIds);
  }

  private Map<UUID, ConsumingPartyView> indexParties() {
    Map<UUID, ConsumingPartyView> byId = new HashMap<>();
    consumingParties.list().forEach(p -> byId.put(p.id(), p));
    return byId;
  }

  private ActivityEventView toView(
      AuditEventView row,
      Map<UUID, String> refsById,
      Map<UUID, ApiKeyOwnerRef> owners,
      Map<UUID, ConsumingPartyView> partiesById) {
    String ref =
        ID_NOT_REF_ACTIONS.contains(row.action())
            ? parseUuid(row.entityRef()).map(refsById::get).orElse(row.entityRef())
            : row.entityRef();

    ConsumingPartyView party = resolveParty(row, owners, partiesById);

    return new ActivityEventView(
        row.action(),
        row.actorType(),
        ref,
        party == null ? null : party.code(),
        party == null ? null : party.nameI18n(),
        row.detail(),
        row.occurredAt());
  }

  private ConsumingPartyView resolveParty(
      AuditEventView row,
      Map<UUID, ApiKeyOwnerRef> owners,
      Map<UUID, ConsumingPartyView> partiesById) {
    UUID partyId = null;
    if ("CONSUME_SCHEMA_DENIED".equals(row.action()) && row.detail() != null) {
      partyId = parseUuid((String) row.detail().get("party")).orElse(null);
    } else if (ACTOR_TYPE_API_KEY.equals(row.actorType()) && row.actorId() != null) {
      ApiKeyOwnerRef owner = owners.get(row.actorId());
      if (owner != null && owner.kind() == ApiKeyOwnerRef.OwnerKind.CONSUMING_PARTY) {
        partyId = owner.ownerId();
      }
    }
    return partyId == null ? null : partiesById.get(partyId);
  }

  private static Optional<UUID> parseUuid(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
