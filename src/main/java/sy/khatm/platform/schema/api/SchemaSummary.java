package sy.khatm.platform.schema.api;

import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * List-view projection of a registered credential schema — the shape {@code GET /api/v1/schemas}
 * returns (KH-1.6-early, console issue-screen dependency).
 *
 * @param id internal UUID, the same value {@link SchemaRef#id()} carries
 * @param nameI18n bilingual display name
 * @param version the schema version
 * @param status the schema's lifecycle status (e.g. {@code PUBLISHED})
 */
public record SchemaSummary(UUID id, LocalizedText nameI18n, int version, String status) {}
