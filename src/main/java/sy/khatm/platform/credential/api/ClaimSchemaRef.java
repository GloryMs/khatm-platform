package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * The redeemed credential's schema, display-shape (spec FS-1.2.1 D4) — just enough for a wallet to
 * label the document without a second round-trip.
 *
 * @param id internal UUID, the same value {@code schema.api.SchemaRef#id()} carries
 * @param nameI18n bilingual display name
 * @param version the schema version
 */
@Schema(name = "ClaimSchemaRef", description = "The redeemed credential's schema, display shape")
public record ClaimSchemaRef(UUID id, LocalizedText nameI18n, int version) {}
