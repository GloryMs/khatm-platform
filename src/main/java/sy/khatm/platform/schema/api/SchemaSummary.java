package sy.khatm.platform.schema.api;

import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * List-view projection of a registered credential schema — the shape {@code GET /api/v1/schemas}
 * returns (KH-1.6-early, console issue-screen dependency).
 *
 * @param id internal UUID, the same value {@link SchemaRef#id()} carries
 * @param code the schema's machine code (e.g. {@code CriminalRecordExtract/v1}) — the value {@code
 *     POST /api/v1/credentials/issue}'s {@code schemaCode} field expects (KH-1.4.3, closing the
 *     console issue-screen's contract gap: valid {@code schemaCode} values are exactly this
 *     endpoint's {@code code} column)
 * @param nameI18n bilingual display name
 * @param version the schema version
 * @param status the schema's lifecycle status (e.g. {@code PUBLISHED})
 */
public record SchemaSummary(
    UUID id, String code, LocalizedText nameI18n, int version, String status) {}
