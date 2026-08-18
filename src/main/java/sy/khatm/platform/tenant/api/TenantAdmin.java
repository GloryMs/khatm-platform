package sy.khatm.platform.tenant.api;

import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Admin-plane SPI for managing tenants (spec FS-2.1 D6) — the surface behind {@code
 * /api/v1/admin/tenants}, guarded by the {@code platform:admin} scope exclusively (spec FS-2.2 D2).
 */
public interface TenantAdmin {

  /**
   * Onboard a new tenant: create its row, provision its first {@code ACTIVE} signing key, and
   * create its default status list ({@code <slug>-<year>}, capacity 131072) — spec D6.
   *
   * <p><b>Resumable by design (spec V3):</b> true atomicity across the tenant row, the key (which
   * touches a keystore file outside any Postgres transaction), and the status list is not
   * achievable. Rather than a compensating delete (the platform's business tables are never deleted
   * — SEC- confirmed) or a partial-failure error code, calling this again with the same {@code
   * slug} resumes onboarding on the existing row if it has no {@code ACTIVE} key yet, rather than
   * throwing a duplicate conflict. Only a slug that already has a fully-onboarded tenant is a
   * genuine {@code KH-TNT-0409}.
   *
   * @param slug lowercase machine slug matching {@code ^[a-z0-9][a-z0-9-_]{1,62}$}; invalid →
   *     {@code KH-TNT-0400}
   * @param nameI18n bilingual display name (both {@code en} and {@code ar} required)
   * @param type {@code GOVERNMENT}, {@code EDUCATION}, {@code PRIVATE}, or {@code OTHER}
   * @param deployMode {@code SAAS}, {@code ONPREM}, or {@code FEDERATED}; {@code null} defaults to
   *     {@code SAAS} (the database column's own default)
   * @return the newly onboarded (or resumed) tenant's view
   */
  TenantView create(String slug, LocalizedText nameI18n, String type, String deployMode);

  /**
   * List every tenant, newest first.
   *
   * @return every tenant's admin view
   */
  List<TenantView> list();

  /**
   * Fetch one tenant by id.
   *
   * @param id the tenant's id
   * @return the tenant's admin view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-TNT-0404} if no such tenant
   *     exists
   */
  TenantView get(UUID id);

  /**
   * Suspend a tenant — its own users/API keys stop authenticating entirely (spec D7), while
   * already-issued credentials keep verifying/consuming and its JWKS/status-list stay public (spec
   * V4). Idempotent.
   *
   * @param id the tenant to suspend
   * @return the updated view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-TNT-0404} if no such tenant
   *     exists
   */
  TenantView suspend(UUID id);

  /**
   * Reactivate a suspended tenant. Idempotent.
   *
   * @param id the tenant to activate
   * @return the updated view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-TNT-0404} if no such tenant
   *     exists
   */
  TenantView activate(UUID id);

  /**
   * Link, re-link, or unlink a tenant's parent (KH-2.6a, spec FS-2.5 §2/§7) — pure organisational
   * metadata (spec §1), never a security or cryptographic boundary. Validated before the write:
   * self-parent, a cycle (the proposed parent is a descendant of {@code id}, or {@code id} itself),
   * the resulting depth exceeding the maximum (three levels, §7 — accounting for {@code id}'s own
   * existing descendants when it already has children), and the proposed parent not being {@code
   * ACTIVE}. {@code V16__tenant_hierarchy.sql}'s trigger is the DB-level backstop for the same
   * invariant.
   *
   * @param id the tenant to link (or unlink)
   * @param parentSlug the new parent's slug, or {@code null}/blank to unlink (making {@code id} a
   *     root)
   * @return the updated view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-TNT-0404} if {@code id} or
   *     the named parent does not exist
   * @throws sy.khatm.platform.shared.error.ValidationException {@code KH-TNT-0422} self-parent,
   *     {@code KH-TNT-1422} cycle, {@code KH-TNT-2422} depth exceeded, or {@code KH-TNT-3422} the
   *     parent is not {@code ACTIVE}
   */
  TenantView setParent(UUID id, String parentSlug);
}
