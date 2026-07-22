package sy.khatm.platform.consumer.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to add a schema to a consuming party's allowlist (KH-1.4.4 D5, {@code POST
 * /api/v1/admin/consuming-parties/{id}/allowed-schemas}).
 *
 * @param schemaId the schema to allow; must exist in the current tenant ({@code KH-CNS-1404}
 *     otherwise)
 */
record AllowSchemaRequest(@NotNull UUID schemaId) {}
