package sy.khatm.platform.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One ancestor in a credential's issuing tenant's lineage (KH-2.6a, spec FS-2.5 §5/§7) — display
 * metadata only (spec FS-2.5 §1: the hierarchy is administrative, never a security or cryptographic
 * signal). Never affects verification's actual trust decision, JWKS resolution, or status-list
 * lookup — those all remain scoped to the credential's own issuing tenant exactly as before this
 * field existed.
 *
 * @param slug the ancestor tenant's machine slug
 * @param nameI18n the ancestor tenant's bilingual display name
 */
@Schema(
    name = "IssuerLineageEntry",
    description = "One ancestor of the credential's issuing tenant")
public record IssuerLineageEntry(String slug, LocalizedText nameI18n) {}
