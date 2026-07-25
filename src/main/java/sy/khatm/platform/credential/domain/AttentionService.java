package sy.khatm.platform.credential.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sy.khatm.platform.consumer.api.ConsumingPartyAdmin;
import sy.khatm.platform.consumer.api.ConsumingPartyView;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaRef;
import sy.khatm.platform.shared.audit.AuditEventView;
import sy.khatm.platform.shared.audit.AuditService;

/**
 * Backs {@code GET /api/v1/attention} (spec FS-1.5.4 #3, KH-1.1.5-BE) — computed on read, no new
 * storage and no scheduled job, matching spec D6: an admin dashboard read this infrequent doesn't
 * justify precomputing anything.
 *
 * <p>Module-private (Java {@code public} only for cross-package access from {@code credential.web}
 * within this same module, same stance as {@link ActivityService}).
 */
@Service
public class AttentionService {

  private static final String ACTION_VERIFY_OK = "CREDENTIAL_VERIFY_OK";
  private static final String ACTION_VERIFY_FAILED = "CREDENTIAL_VERIFY_FAILED";
  private static final String ACTION_SCHEMA_DENIED = "CONSUME_SCHEMA_DENIED";

  private final AuditService audit;
  private final SchemaCatalog schemas;
  private final ConsumingPartyAdmin consumingParties;
  private final Duration schemaDeniedWindow;
  private final int schemaDeniedCap;
  private final Duration verifyWindow;
  private final double verifyFailureMultiplier;
  private final long verifyMinVolume;

  public AttentionService(
      AuditService audit,
      SchemaCatalog schemas,
      ConsumingPartyAdmin consumingParties,
      @Value("${khatm.stats.attention.window:PT24H}") Duration schemaDeniedWindow,
      @Value("${khatm.stats.attention.cap:20}") int schemaDeniedCap,
      @Value("${khatm.stats.attention.verify-window:PT1H}") Duration verifyWindow,
      @Value("${khatm.stats.attention.verify-failure-multiplier:3.0}")
          double verifyFailureMultiplier,
      @Value("${khatm.stats.attention.verify-min-volume:5}") long verifyMinVolume) {
    this.audit = audit;
    this.schemas = schemas;
    this.consumingParties = consumingParties;
    this.schemaDeniedWindow = schemaDeniedWindow;
    this.schemaDeniedCap = schemaDeniedCap;
    this.verifyWindow = verifyWindow;
    this.verifyFailureMultiplier = verifyFailureMultiplier;
    this.verifyMinVolume = verifyMinVolume;
  }

  /** The full needs-attention feed, both item types combined, newest-relevant first. */
  public List<AttentionItem> attention() {
    List<AttentionItem> items = new ArrayList<>(schemaDeniedItems());
    verifyFailureRateItem().ifPresent(items::add);
    return items;
  }

  private List<AttentionItem> schemaDeniedItems() {
    Instant windowStart = Instant.now().minus(schemaDeniedWindow);
    List<AuditEventView> rows = audit.recentEvents(schemaDeniedCap, List.of(ACTION_SCHEMA_DENIED));

    Map<UUID, ConsumingPartyView> partiesById = new HashMap<>();
    consumingParties.list().forEach(p -> partiesById.put(p.id(), p));

    List<AttentionItem> items = new ArrayList<>();
    for (AuditEventView row : rows) {
      if (row.occurredAt().isBefore(windowStart)) {
        continue;
      }
      items.add(toSchemaDeniedItem(row, partiesById));
    }
    return items;
  }

  private AttentionItem toSchemaDeniedItem(
      AuditEventView row, Map<UUID, ConsumingPartyView> partiesById) {
    Map<String, Object> detail = new HashMap<>();
    detail.put("credentialRef", row.entityRef());

    Optional<UUID> schemaId =
        row.detail() == null ? Optional.empty() : parseUuid((String) row.detail().get("schemaId"));
    schemaId.ifPresent(
        id -> {
          detail.put("schemaId", id.toString());
          schemas
              .findById(id)
              .map(SchemaRef::code)
              .ifPresent(code -> detail.put("schemaCode", code));
        });

    Optional<UUID> partyId =
        row.detail() == null ? Optional.empty() : parseUuid((String) row.detail().get("party"));
    partyId.ifPresent(
        id -> {
          detail.put("partyId", id.toString());
          ConsumingPartyView party = partiesById.get(id);
          if (party != null) {
            detail.put("partyCode", party.code());
            detail.put("partyName", party.nameI18n());
          }
        });

    return new AttentionItem(AttentionItem.TYPE_SCHEMA_DENIED, row.occurredAt(), detail);
  }

  private Optional<AttentionItem> verifyFailureRateItem() {
    Instant now = Instant.now();
    Instant currentFrom = now.minus(verifyWindow);
    Instant priorFrom = currentFrom.minus(verifyWindow);

    Map<String, Long> current = audit.countActionsInWindow(currentFrom, now);
    Map<String, Long> baseline = audit.countActionsInWindow(priorFrom, currentFrom);

    long currentOk = current.getOrDefault(ACTION_VERIFY_OK, 0L);
    long currentFailed = current.getOrDefault(ACTION_VERIFY_FAILED, 0L);
    long currentTotal = currentOk + currentFailed;

    long baselineOk = baseline.getOrDefault(ACTION_VERIFY_OK, 0L);
    long baselineFailed = baseline.getOrDefault(ACTION_VERIFY_FAILED, 0L);
    long baselineTotal = baselineOk + baselineFailed;

    if (currentTotal < verifyMinVolume || currentFailed == 0) {
      return Optional.empty();
    }

    double currentFailureRate = (double) currentFailed / currentTotal;
    double baselineFailureRate = baselineTotal == 0 ? 0.0 : (double) baselineFailed / baselineTotal;

    if (currentFailureRate < verifyFailureMultiplier * baselineFailureRate) {
      return Optional.empty();
    }

    Map<String, Object> detail = new HashMap<>();
    detail.put("currentWindowFrom", currentFrom.toString());
    detail.put("currentWindowTo", now.toString());
    detail.put("currentTotal", currentTotal);
    detail.put("currentFailed", currentFailed);
    detail.put("currentFailureRate", currentFailureRate);
    detail.put("baselineWindowFrom", priorFrom.toString());
    detail.put("baselineWindowTo", currentFrom.toString());
    detail.put("baselineTotal", baselineTotal);
    detail.put("baselineFailureRate", baselineFailureRate);
    detail.put("multiplierThreshold", verifyFailureMultiplier);
    detail.put("minVolumeThreshold", verifyMinVolume);

    return Optional.of(new AttentionItem(AttentionItem.TYPE_VERIFY_FAILURE_RATE, now, detail));
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
