package sy.khatm.platform.issuerclient.domain;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.issuerclient.api.IssuerClientSchemaAccess;

/**
 * {@link IssuerClientSchemaAccess} over {@code issuer_client_schema}. Runs under the ambient
 * tenant's RLS scope (the issuance request already carries the client's own tenant), so a schema id
 * from another tenant can never appear on an allowlist to begin with.
 */
@Service
class IssuerClientSchemaAccessService implements IssuerClientSchemaAccess {

  private final IssuerClientRepository repository;

  IssuerClientSchemaAccessService(IssuerClientRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isSchemaAllowed(UUID clientId, UUID schemaId) {
    return repository.existsAllowedSchema(clientId, schemaId);
  }
}
