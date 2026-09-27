package sy.khatm.platform.issuerclient.api;

import java.util.UUID;

/**
 * The per-client schema allowlist check (spec FS-2.7a D1's {@code issuer_client_schema}) — the
 * issuer-side twin of {@code consumer.api.ConsumingPartyRegistry#isSchemaAllowed}. Deny-by-default:
 * a client with no allowlist entries can issue nothing.
 */
public interface IssuerClientSchemaAccess {

  /**
   * Whether {@code clientId} may issue credentials against {@code schemaId}.
   *
   * @param clientId the {@code issuer_client.id}
   * @param schemaId the schema being issued against
   * @return {@code true} only if the pair is on the client's allowlist
   */
  boolean isSchemaAllowed(UUID clientId, UUID schemaId);
}
