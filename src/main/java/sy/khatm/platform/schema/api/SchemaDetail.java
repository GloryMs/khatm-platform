package sy.khatm.platform.schema.api;

import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Detail-view projection of a registered credential schema — the shape {@code GET
 * /api/v1/schemas/{id}} returns (KH-1.6-early, console issue-screen dependency): {@link
 * SchemaSummary}'s fields plus the raw claims definition needed to render an issue form.
 *
 * @param id internal UUID, the same value {@link SchemaRef#id()} carries
 * @param nameI18n bilingual display name
 * @param version the schema version
 * @param status the schema's lifecycle status (e.g. {@code PUBLISHED})
 * @param claimsDefJson raw JSON text describing claim fields (type, required, label_i18n per field)
 */
public record SchemaDetail(
    UUID id, LocalizedText nameI18n, int version, String status, String claimsDefJson) {}
