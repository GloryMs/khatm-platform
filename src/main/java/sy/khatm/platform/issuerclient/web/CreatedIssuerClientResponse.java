package sy.khatm.platform.issuerclient.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The one-time response to creating or rotating a client.
 *
 * @param id the new client's id
 * @param keyPrefix the lookup prefix (also in the listing)
 * @param apiKey the full {@code khi_...} key — shown ONLY here, never retrievable again
 * @param holderHmacSecret the tenant holder secret — present ONLY when this call generated it (the
 *     first client of a root tenant), shown once
 * @param retiringClientId on rotation, the client that entered its grace window
 * @param client the new client as listed
 */
@Schema(name = "CreatedIssuerClientResponse")
record CreatedIssuerClientResponse(
    UUID id,
    String keyPrefix,
    String apiKey,
    String holderHmacSecret,
    UUID retiringClientId,
    IssuerClientResponse client) {

  /** Keeps the one-time secrets out of any accidental log line. */
  @Override
  public String toString() {
    return "CreatedIssuerClientResponse[id=" + id + "]";
  }
}
