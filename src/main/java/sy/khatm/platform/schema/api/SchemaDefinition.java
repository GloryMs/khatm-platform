package sy.khatm.platform.schema.api;

import java.util.List;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Definition of a credential schema to register, for callers that need one to exist before issuing
 * against it (e.g. the demo seeder).
 *
 * <p>Real schema authoring (console-driven, versioned, with a typed claims editor) is KH-1.x work;
 * this DTO covers only what {@link SchemaCatalog#ensurePublished(SchemaDefinition)} needs.
 *
 * @param code machine code, e.g. {@code CriminalRecordExtract}
 * @param version schema version; {@code 1} for a first release
 * @param nameI18n bilingual display name
 * @param claimsDefJson raw JSON text describing claim fields (type, required, label_i18n)
 * @param sdFields names of claims eligible for selective disclosure
 * @param defaultMaxUses default max consumption count for credentials issued against this schema
 * @param requiresAttestation whether a credential issued against this schema must carry an {@code
 *     attestation} object (KH-2.4, spec FS-2.4 item 1) — {@code false} for every find-or-create
 *     caller that predates attested-document support (e.g. the quick-issue path)
 */
public record SchemaDefinition(
    String code,
    int version,
    LocalizedText nameI18n,
    String claimsDefJson,
    List<String> sdFields,
    int defaultMaxUses,
    boolean requiresAttestation) {}
