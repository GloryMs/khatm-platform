package sy.khatm.platform.issuerclient.web;

import org.springframework.stereotype.Component;
import sy.khatm.platform.issuerclient.domain.CreatedIssuerClient;
import sy.khatm.platform.issuerclient.domain.IssuerClientView;

/** Manual entity/view-to-DTO mapping for the issuer-client web layer (work rule 4). */
@Component
class IssuerClientMapper {

  IssuerClientResponse toResponse(IssuerClientView v) {
    return new IssuerClientResponse(
        v.id(),
        v.tenantId(),
        v.name(),
        v.keyPrefix(),
        v.scopes(),
        v.status(),
        v.allowedSchemaIds(),
        v.rotatedFrom(),
        v.retireAfter(),
        v.expiresAt(),
        v.lastUsedAt(),
        v.createdAt());
  }

  CreatedIssuerClientResponse toCreatedResponse(CreatedIssuerClient c) {
    return new CreatedIssuerClientResponse(
        c.client().id(),
        c.client().keyPrefix(),
        c.apiKey(),
        c.holderHmacSecret(),
        c.retiringClientId(),
        toResponse(c.client()));
  }
}
