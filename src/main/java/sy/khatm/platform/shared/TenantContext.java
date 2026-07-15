package sy.khatm.platform.shared;

import java.util.UUID;

/**
 * Provisional tenant context for the single-tenant MVP.
 *
 * <p>Full multi-tenancy (per-request tenant resolution, Postgres RLS) arrives in KH-2.1. Until then
 * every module writes rows under {@link #DEFAULT_TENANT_ID} — the fixed UUID seeded by {@code
 * V1__baseline.sql} (spec FS-0.2 §"Seed"). Every business table already carries a {@code tenant_id
 * NOT NULL} column so that KH-2.1 does not require a schema migration, only a behavioral change in
 * how this class resolves the tenant.
 */
public final class TenantContext {

  /** The single default tenant every row belongs to until KH-2.1. Must match V1__baseline.sql. */
  public static final UUID DEFAULT_TENANT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** The default tenant's slug. Must match the {@code tenant.slug} seeded by V1__baseline.sql. */
  public static final String DEFAULT_TENANT_SLUG = "khatm-default";

  private TenantContext() {}

  /**
   * Resolve the current tenant id.
   *
   * <p>Always {@link #DEFAULT_TENANT_ID} until KH-2.1 introduces per-request tenant resolution.
   *
   * @return the current tenant id
   */
  public static UUID current() {
    return DEFAULT_TENANT_ID;
  }

  /**
   * Resolve the current tenant's slug.
   *
   * <p>Always {@link #DEFAULT_TENANT_SLUG} until KH-2.1. Used e.g. by the {@code key} module to
   * build {@code kid} values ({@code {tenant-slug}:key-{seq}}, spec FS-0.5 §4) without needing a
   * cross-module dependency on the {@code tenant} module, which has no {@code api} sub-package yet.
   *
   * @return the current tenant's slug
   */
  public static String currentSlug() {
    return DEFAULT_TENANT_SLUG;
  }
}
