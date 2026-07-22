package sy.khatm.platform.consumer.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Admin-plane view of a registered consuming party (KH-1.4.4, {@code
 * /api/v1/admin/consuming-parties}) — the shape the console's consuming-parties screen renders.
 *
 * <p>Unlike {@link ConsumingPartyRef} (the minimal {@code id + code} the consume path needs), this
 * carries the full management projection, including the party's schema allowlist resolved to
 * human-meaningful {@code (schemaId, schemaCode)} pairs.
 *
 * @param id internal UUID — the {@code consumption_event.consuming_party_id} foreign key, and the
 *     path id for suspend/activate/allow/mint operations
 * @param code the party's machine code (lowercase slug), unique within the tenant
 * @param nameI18n bilingual display name
 * @param status {@code ACTIVE} or {@code SUSPENDED}
 * @param createdAt when the party was first registered
 * @param allowedSchemas the schemas this party may consume, deny-by-default (empty = may consume
 *     nothing) — resolved from the {@code consuming_party_schema} join table
 */
public record ConsumingPartyView(
    UUID id,
    String code,
    LocalizedText nameI18n,
    String status,
    Instant createdAt,
    List<AllowedSchema> allowedSchemas) {

  /**
   * One entry in a party's schema allowlist.
   *
   * @param schemaId the allowed schema's internal id
   * @param schemaCode the allowed schema's machine code (e.g. {@code CriminalRecordExtract/v1}), or
   *     {@code null} if the schema row no longer resolves (defensive — an allowlist row should
   *     never outlive its schema)
   */
  public record AllowedSchema(UUID schemaId, String schemaCode) {}
}
