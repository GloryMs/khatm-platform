package sy.khatm.platform.key.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/admin/signing-keys/rotate} (spec FS-2.3 D5/D6, KH-2.3b).
 *
 * @param provider the provider the new key should be created on ({@code SOFT}/{@code VAULT});
 *     {@code null}/absent rotates onto the same provider the tenant's current {@code ACTIVE} key
 *     already uses (the pre-KH-2.3b behavior, unchanged). Naming a different provider here is the
 *     entire SOFT→Vault (or any provider→provider) migration mechanism — spec D6's "migration is
 *     nothing but a normal rotation."
 */
@Schema(
    name = "RotateKeyRequest",
    description = "Optional provider override — the SOFT->Vault migration mechanism (spec D6)")
public record RotateKeyRequest(String provider) {}
