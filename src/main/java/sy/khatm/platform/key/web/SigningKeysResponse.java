package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Result of {@code GET /api/v1/admin/signing-keys} (spec FS-1.5.4 #4, KH-1.1.5-BE) — the console's
 * Dashboard v2 signing-key status panel.
 *
 * @param keys every signing key for the tenant, newest first, every state including {@code RETIRED}
 */
@Schema(name = "SigningKeysResponse", description = "Every signing key's lifecycle status")
public record SigningKeysResponse(List<SigningKeyView> keys) {}
