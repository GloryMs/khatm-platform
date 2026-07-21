package sy.khatm.platform.schema.api;

import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Opaque reference to a registered credential schema — the only schema data other modules may see.
 *
 * @param id internal UUID, used as the {@code credential.schema_id} foreign key
 * @param code the schema's machine code (e.g. {@code CriminalRecordExtract})
 * @param version the schema version
 * @param nameI18n bilingual display name (KH-1.1.4 — {@code credential} search-result rows show
 *     this alongside {@code code})
 * @param claimsDefJson raw JSON text describing claim fields (type, required, label_i18n per
 *     field); the full set of field names is the SD-JWT issuance path's source of truth for which
 *     claims must become disclosures (spec FS-0.4 D1)
 * @param sdFields names of claims_def fields the holder is permitted to withhold at presentation
 *     time — every other claims_def field is mandatory to disclose (spec FS-0.4 D2); enforced by
 *     the verify path, not the DB schema itself
 */
public record SchemaRef(
    UUID id,
    String code,
    int version,
    LocalizedText nameI18n,
    String claimsDefJson,
    List<String> sdFields) {}
