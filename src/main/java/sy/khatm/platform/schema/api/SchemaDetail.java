package sy.khatm.platform.schema.api;

import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Detail-view projection of a registered credential schema — the shape {@code GET
 * /api/v1/schemas/{id}} returns (KH-1.6-early, console issue-screen dependency): {@link
 * SchemaSummary}'s fields plus the raw claims definition and the issuance defaults needed to render
 * and pre-fill an issue form (KH-1.4.3).
 *
 * @param id internal UUID, the same value {@link SchemaRef#id()} carries
 * @param code the schema's machine code — see {@link SchemaSummary#code()}
 * @param nameI18n bilingual display name
 * @param version the schema version
 * @param status the schema's lifecycle status (e.g. {@code PUBLISHED})
 * @param claimsDefJson raw JSON text describing claim fields (type, required, label_i18n per field)
 * @param sdFields names of {@code claimsDefJson} fields the holder is permitted to withhold at
 *     presentation time — every other field is mandatory to disclose (spec FS-0.4 D2)
 * @param defaultMaxUses default max consumption count for credentials issued against this schema
 * @param defaultValidity default validity window as an ISO-8601 duration string (e.g. {@code
 *     "P90D"}), or {@code null} if the schema has no configured default
 */
public record SchemaDetail(
    UUID id,
    String code,
    LocalizedText nameI18n,
    int version,
    String status,
    String claimsDefJson,
    List<String> sdFields,
    int defaultMaxUses,
    String defaultValidity) {}
