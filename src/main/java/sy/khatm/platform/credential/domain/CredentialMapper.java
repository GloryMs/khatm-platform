package sy.khatm.platform.credential.domain;

import java.time.Instant;
import org.springframework.stereotype.Component;
import sy.khatm.platform.credential.api.CredentialView;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaRef;

/**
 * Maps {@link Credential} entities to the {@link CredentialView} DTO (CONVENTIONS.md §5 — manual
 * mapper class per module, no inline mapping in services/controllers).
 *
 * <p>Resolving {@code schemaCode} requires {@link SchemaCatalog} because the entity only stores
 * {@code schema_id}; the view needs the human-readable code.
 *
 * <p>This class is module-private.
 */
@Component
class CredentialMapper {

  private final SchemaCatalog schemas;

  CredentialMapper(SchemaCatalog schemas) {
    this.schemas = schemas;
  }

  CredentialView toView(Credential c) {
    String schemaCode = schemas.findById(c.getSchemaId()).map(SchemaRef::code).orElse(null);
    return new CredentialView(
        c.getId().toString(),
        c.getRef(),
        schemaCode,
        c.getUsesRemaining(),
        c.getMaxUses(),
        c.isRevoked(),
        c.getValidTo(),
        c.getSignedPayload(),
        CredentialStatus.derive(c, Instant.now()).name(),
        c.getMaxUses() - c.getUsesRemaining());
  }
}
