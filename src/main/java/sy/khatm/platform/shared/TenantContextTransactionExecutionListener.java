package sy.khatm.platform.shared;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Sets the Postgres session variable {@code app.tenant_id} at the start of every physical database
 * transaction, transaction-scoped (spec FS-2.1 D4) — the mechanism Row Level Security's {@code
 * tenant_isolation} policy (V7__rls_policies.sql) reads via {@code current_setting('app.tenant_id',
 * true)}.
 *
 * <p><b>Transaction-scoped, never session-scoped (hard constraint):</b> {@code SELECT
 * set_config('app.tenant_id', ?, true)} — the third argument, {@code is_local = true}, is exactly
 * Postgres's {@code SET LOCAL} semantics: the value is automatically discarded at commit/rollback.
 * {@code SET SESSION} is forbidden here on purpose — a connection pool (HikariCP) reuses physical
 * connections across unrelated requests/transactions, and a session-scoped setting would leak
 * tenant A's context into tenant B's next transaction on the same pooled connection.
 *
 * <p>Registered on every {@link org.springframework.orm.jpa.JpaTransactionManager} bean by {@link
 * TenantContextPropagationConfig}'s {@code BeanPostProcessor} — {@link #afterBegin} fires once per
 * physical transaction (a nested {@code @Transactional(propagation = REQUIRED)} call joining an
 * existing transaction does not trigger a second physical {@code doBegin}, so this does not
 * re-execute per nested call).
 *
 * <p>Resolves {@link TenantContext#currentIdForTransactionPropagation()} — which falls back to
 * {@link TenantContext#DEFAULT_TENANT_ID} when nothing set it explicitly — so every pre-KH-2.1 code
 * path (seeders, the default tenant's own traffic, any test that never touches tenant machinery)
 * keeps working unchanged under RLS: {@code app.tenant_id} is always set to *something* correct for
 * the calling thread, never left unset. Deliberately the unguarded accessor, not {@link
 * TenantContext#current()} (KH-2.1 review follow-up's fail-fast guard) — this listener fires on
 * every physical transaction, including the one {@code rbac.security.TenantContextFilter} itself
 * uses to look up which tenant to set in the first place; that lookup's own transaction begins
 * before {@code TenantContext.set} can run, with a real authenticated principal already on the
 * {@code SecurityContextHolder} — the guarded accessor would rightly reject that shape from
 * application code, but here it is the filter's own necessary chicken-and-egg step, not a bypass.
 */
class TenantContextTransactionExecutionListener implements TransactionExecutionListener {

  private final JdbcTemplate jdbcTemplate;

  TenantContextTransactionExecutionListener(DataSource dataSource) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Override
  public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
    if (beginFailure != null) {
      return;
    }
    String tenantId = TenantContext.currentIdForTransactionPropagation().toString();
    jdbcTemplate.queryForObject(
        "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
  }
}
