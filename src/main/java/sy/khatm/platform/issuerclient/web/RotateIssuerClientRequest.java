package sy.khatm.platform.issuerclient.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request to rotate an issuer client's key.
 *
 * @param retireAfterHours grace window during which the old key keeps working, 0-72; omitted = 24
 */
@Schema(name = "RotateIssuerClientRequest")
record RotateIssuerClientRequest(Integer retireAfterHours) {}
