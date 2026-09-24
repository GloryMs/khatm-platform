package sy.khatm.platform.issuerclient.domain;

import java.util.UUID;

/**
 * The one-time result of creating or rotating an issuer client.
 *
 * <p>{@code apiKey} and {@code holderHmacSecret} exist only in this object and the HTTP response
 * built from it — nothing persists or logs them.
 *
 * @param client the new client (no key material)
 * @param apiKey the raw {@code khi_...} key, shown once
 * @param holderHmacSecret the tenant holder secret if this call generated it (first client of a
 *     root tenant), otherwise {@code null}
 * @param retiringClientId on rotation, the client now {@code RETIRING}; {@code null} on plain
 *     creation
 */
public record CreatedIssuerClient(
    IssuerClientView client, String apiKey, String holderHmacSecret, UUID retiringClientId) {

  /** Never render key material, even by accident in a log line. */
  @Override
  public String toString() {
    return "CreatedIssuerClient[client=" + client.id() + "]";
  }
}
