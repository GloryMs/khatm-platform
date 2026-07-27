package sy.khatm.platform.shared;

import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
 *
 * <p><b>Fail-fast guard (KH-2.1 review follow-up):</b> the default-tenant fallback above is
 * deliberately silent — but silence is only safe for requests that genuinely have no tenant to
 * resolve (an anonymous caller, a worker, a seeder, a test). It is NOT safe for an authenticated,
 * non-anonymous request that somehow reaches this class without {@code
 * rbac.security.TenantContextFilter} having run first: under RLS that cannot leak across tenants
 * (the query itself would just see nothing), but it CAN silently misattribute the request's
 * reads/writes to the default tenant instead of failing loudly. {@link #current()}/{@link
 * #currentSlug()} therefore check {@link SecurityContextHolder}: if it holds a real, authenticated,
 * non-{@link AnonymousAuthenticationToken} principal but nothing was ever set on this thread, that
 * is exactly the "bypassed the filter" shape, and they throw rather than fall back. Every current
 * call site is safe against this — verified by reading, not inferred: the five genuinely anonymous
 * HTTP paths ({@code SecurityConfig}'s {@code VERIFY_PATH}/{@code JWKS_PATH}/{@code
 * TENANT_JWKS_PATH}/{@code STATUS_LIST_PATH}/{@code CLAIMS_REDEEM_PATH}) always carry an {@code
 * AnonymousAuthenticationToken} when hit without a session (the guard's predicate is false, exactly
 * the legal fallback case); {@code SystemAccessExecutor}-wrapped worker/anonymous-lookup code paths
 * run on threads Spring Security's {@code SecurityContextHolder} never populates at all ({@code
 * getAuthentication()} is {@code null}); and every seeder/test either runs with no {@code
 * SecurityContext} either, or (for the {@code RbacHttpTestSupport}-based HTTP tests) goes through
 * the real filter chain, which always sets this before a real principal's request reaches
 * application code.
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
   *     nothing has been set and the fallback is legal (see the class Javadoc's fail-fast guard)
   * @throws IllegalStateException if a real, authenticated, non-anonymous principal is on the
   *     {@link SecurityContextHolder} but nothing was ever set on this thread
   */
  public static UUID current() {
    TenantSnapshot snapshot = CURRENT.get();
    if (snapshot != null) {
      return snapshot.id();
    }
    requireLegalFallback();
    return DEFAULT_TENANT_ID;
  }

  /**
   * Resolve the current tenant's slug. Used e.g. by the {@code key} module to build {@code kid}
   * values ({@code {tenant-slug}:key-{seq}}, spec FS-0.5 §4) without needing a cross-module
   * dependency on the {@code tenant} module.
   *
   * @return the slug set by {@link #set} on this thread, or {@link #DEFAULT_TENANT_SLUG} if nothing
   *     has been set and the fallback is legal (see the class Javadoc's fail-fast guard)
   * @throws IllegalStateException if a real, authenticated, non-anonymous principal is on the
   *     {@link SecurityContextHolder} but nothing was ever set on this thread
   */
  public static String currentSlug() {
    TenantSnapshot snapshot = CURRENT.get();
    if (snapshot != null) {
      return snapshot.slug();
    }
    requireLegalFallback();
    return DEFAULT_TENANT_SLUG;
  }

  /**
   * Resolve the current tenant id, bypassing the fail-fast guard {@link #current()} applies —
   * package-private, for {@link TenantContextTransactionExecutionListener}'s use only.
   *
   * <p>That listener fires on <em>every</em> physical database transaction — including the one
   * {@code rbac.security.TenantContextFilter} itself uses to look up which tenant to {@link #set},
   * which necessarily begins before that {@code set} call can happen. A real, authenticated,
   * non-anonymous principal is already on the {@link SecurityContextHolder} at that point (the
   * filter only reaches the lookup after resolving one) — exactly the shape {@link #current()}'s
   * guard exists to reject, but here it is not a bypassed filter, it is that filter's own
   * legitimate, unavoidable chicken-and-egg step. This listener's job is purely to propagate
   * whatever the {@link ThreadLocal} currently holds — set or not — to Postgres; it is
   * infrastructure plumbing, never an HTTP-authentication judgment call, so it must never throw.
   *
   * @return the tenant id set by {@link #set} on this thread, or {@link #DEFAULT_TENANT_ID} if
   *     nothing has been set, unconditionally — never throws
   */
  static UUID currentIdForTransactionPropagation() {
    TenantSnapshot snapshot = CURRENT.get();
    return snapshot != null ? snapshot.id() : DEFAULT_TENANT_ID;
  }

  /**
   * The default-tenant fallback is illegal exactly when {@link SecurityContextHolder} holds a real
   * (non-anonymous) authenticated principal — that shape only exists if {@code
   * rbac.security.TenantContextFilter} was bypassed, since it always calls {@link #set} for one.
   * Anonymous requests, worker threads, seeders, and most tests never populate an authenticated,
   * non-anonymous {@link Authentication} at all, so this is a no-op for every legal caller.
   */
  private static void requireLegalFallback() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      throw new IllegalStateException(
          "TenantContext read with an authenticated, non-anonymous principal on the"
              + " SecurityContext but no tenant was ever set on this thread — this would silently"
              + " misattribute the request to the default tenant instead of failing. Every"
              + " authenticated route must run behind rbac.security.TenantContextFilter.");
    }
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
