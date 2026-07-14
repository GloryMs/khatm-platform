package sy.khatm.platform.schema.api;

import java.util.UUID;

/**
 * Opaque reference to a registered credential schema — the only schema data other modules may see.
 *
 * @param id internal UUID, used as the {@code credential.schema_id} foreign key
 * @param code the schema's machine code (e.g. {@code CriminalRecordExtract})
 * @param version the schema version
 */
public record SchemaRef(UUID id, String code, int version) {}
