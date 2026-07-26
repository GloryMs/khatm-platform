package sy.khatm.platform.shared;

import java.util.UUID;

/**
 * Resolves the tenant the current thread of execution is acting on behalf of (spec FS-2.1 D1).
 *
 * <p>Backed by a {@link ThreadLocal}, not a Spring request-scoped bean: a Redis Streams worker
 * thread (KH-2.1 Part B) has no active {@code HttpServletRequest} to scope to, so a plain
 * ThreadLocal is the one mechanism that works uniformly for both {@code
 * rbac.security.TenantContextFilter} (populates it per HTTP request from the authenticated
 * principal) and worker-side restoration from an event payload. {@link #set}/{@link #clear} must
 * always be paired in a try/finally by whichever caller sets it — servlet containers and worker
 * dispatch loops both reuse threads across unrelated requests/events, so a value left set would
 * leak into unrelated work on the same thread.
 *
 * <p>When nothing has called {@link #set} on the current thread (an anonymous request — {@code
 * /verify}, {@code /api/v1/claims/redeem}, {@code /sl/**}, JWKS — or a startup-time {@code
 * ApplicationRunner} such as {@code key.domain.KeyBootstrap}), {@link #current()}/{@link
 * #currentSlug()} fall back to {@link #DEFAULT_TENANT_ID}/{@link #DEFAULT_TENANT_SLUG} — the single
 * tenant every row belonged to before KH-2.1. That fallback is also what keeps every seeder and the
 * {@code local} profile's zero-setup demo data working unchanged.
 */
public final class TenantContext {

  /** The platform's original single tenant. Must match the row seeded by V1__baseline.sql. */
  public static final UUID DEFAULT_TENANT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** The default tenant's slug. Must match the {@code tenant.slug} seeded by V1__baseline.sql. */
  public static final String DEFAULT_TENANT_SLUG = "khatm-default";

  private static final ThreadLocal<TenantSnapshot> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  /**
   * Resolve the current tenant id.
   *
   * @return the tenant id set by {@link #set} on this thread, or {@link #DEFAULT_TENANT_ID} if
   *     nothing has been set
   */
  public static UUID current() {
    TenantSnapshot snapshot = CURRENT.get();
    return snapshot != null ? snapshot.id() : DEFAULT_TENANT_ID;
  }

  /**
   * Resolve the current tenant's slug. Used e.g. by the {@code key} module to build {@code kid}
   * values ({@code {tenant-slug}:key-{seq}}, spec FS-0.5 §4) without needing a cross-module
   * dependency on the {@code tenant} module.
   *
   * @return the slug set by {@link #set} on this thread, or {@link #DEFAULT_TENANT_SLUG} if nothing
   *     has been set
   */
  public static String currentSlug() {
    TenantSnapshot snapshot = CURRENT.get();
    return snapshot != null ? snapshot.slug() : DEFAULT_TENANT_SLUG;
  }

  /**
   * Set the tenant this thread is acting on behalf of for the remainder of its current unit of
   * work. Callers (the servlet filter, worker dispatch) must clear it in a {@code finally} block.
   *
   * @param id the resolved tenant's id
   * @param slug the resolved tenant's slug
   */
  public static void set(UUID id, String slug) {
    CURRENT.set(new TenantSnapshot(id, slug));
  }

  /** Clear whatever tenant was set on this thread — always call from a {@code finally} block. */
  public static void clear() {
    CURRENT.remove();
  }

  private record TenantSnapshot(UUID id, String slug) {}
}
