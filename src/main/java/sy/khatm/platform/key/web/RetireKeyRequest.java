package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/admin/signing-keys/{kid}/retire} (spec FS-2.3 D4, KH-2.3a).
 *
 * @param force bypass the {@code khatm.keys.min-retiring-age} guard; {@code null}/absent is treated
 *     as {@code false}. Always audited ({@code detail.forced} on the {@code KEY_RETIRED} row)
 *     regardless of which path actually retired the key.
 */
@Schema(name = "RetireKeyRequest", description = "Optional min-age bypass for retiring a key")
public record RetireKeyRequest(Boolean force) {

  /**
   * @return {@code force}, or {@code false} if absent
   */
  public boolean forceOrDefault() {
    return force != null && force;
  }
}
