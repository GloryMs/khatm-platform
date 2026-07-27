package sy.khatm.platform.shared;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an action with the Postgres session variable {@code app.khatm_system} set to {@code 'on'}
 * for the duration of the current transaction (spec FS-2.1 D5) — the mechanism the {@code
 * system_access} Row Level Security policy (V7__rls_policies.sql) on every business table reads via
 * {@code current_setting('app.khatm_system', true) = 'on'}.
 *
 * <p><b>Only for the enumerated, genuinely anonymous read paths</b> — a request that has no
 * authenticated principal to resolve a tenant from at all: redeem lookup, verify lookup,
 * status-list read, JWKS read, API-key verification ({@code rbac.domain.ApiKeyService#verify} —
 * resolving the tenant is the whole point of that lookup, so it cannot rely on {@code
 * TenantContext}'s ambient value either), and the cross-tenant sweep workers ({@code
 * credential.worker.ClaimCodeExpiryWorker}, {@code status.worker.StatusListPublishSweepWorker}).
 * {@code shared.SystemAccessCallerAllowlistTest} asserts the set of call sites matches this
 * enumeration exactly — a new caller is a deliberate decision, not a silent addition.
 *
 * <p><b>Transaction-scoped, never session-scoped</b> — same {@code set_config(..., true)} shape and
 * rationale as {@link TenantContextTransactionExecutionListener}; this class is
 * {@code @Transactional} itself precisely because {@code is_local = true} only behaves as {@code
 * SET LOCAL} when a transaction is actually active (per Postgres's own documented behavior, it
 * silently falls back to session-scope otherwise) — {@code action} runs inside that same
 * transaction/ connection via Spring's default {@code REQUIRED} propagation, so any
 * {@code @Transactional} method it calls joins this one rather than opening a second physical
 * transaction.
 */
@Component
public class SystemAccessExecutor {

  private final JdbcTemplate jdbcTemplate;

  public SystemAccessExecutor(DataSource dataSource) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
  }

  /**
   * Run {@code action} with {@code app.khatm_system = 'on'} set for this transaction.
   *
   * @param action the read to perform under system access
   * @param <T> the action's result type
   * @return the action's result
   */
  @Transactional
  public <T> T runAsSystem(Supplier<T> action) {
    jdbcTemplate.queryForObject("SELECT set_config('app.khatm_system', 'on', true)", String.class);
    return action.get();
  }
}
