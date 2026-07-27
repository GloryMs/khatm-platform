package sy.khatm.platform.support;

import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * KH-2.1-BE Part B (spec FS-2.1 D2/D4) — makes every {@code @Autowired JdbcTemplate} call in the
 * shared-context integration test suite run inside its own fresh transaction, so {@code
 * shared.TenantContextTransactionExecutionListener} fires and sets {@code app.tenant_id} for it.
 *
 * <p><b>Why this exists:</b> RLS's {@code tenant_isolation} policy requires {@code app.tenant_id}
 * to be set, and D4's hard constraint (never {@code SET SESSION}) means that setting is
 * transaction-scoped only — Postgres's {@code SET LOCAL} semantics only take effect inside an
 * actual transaction. Dozens of existing tests call a service method (which commits in its own real
 * transaction), then run a bare, unwrapped {@code jdbc.queryForObject(...)} afterward to verify the
 * row landed — exactly the "verify after commit" pattern integration tests should use. Before this
 * fix, that bare call ran in plain autocommit mode with no Spring transaction at all, so the
 * tenant-context hook never fired and the query saw zero rows under RLS, regardless of which tenant
 * the data actually belonged to — a test-infrastructure gap, not an application bug (every real
 * request path already runs inside a genuine {@code @Transactional} service method).
 *
 * <p>Implemented as a transactional proxy (not a {@code JdbcTemplate} subclass overriding each
 * method) via a plain {@link TransactionInterceptor} with {@code PROPAGATION_REQUIRED} matched
 * against every method name — CGLIB-proxies the concrete class since existing tests
 * {@code @Autowired} the class type directly, not an interface. {@code REQUIRED} rather than {@code
 * REQUIRES_NEW} is deliberate: the majority of call sites have no ambient transaction at all (call
 * a service, then verify via a bare {@code jdbc} call afterward) — {@code REQUIRED} opens a fresh
 * physical transaction for these exactly as {@code REQUIRES_NEW} would. But a handful of tests
 * (e.g. {@code ClaimCodeExpirySweepTest}, the {@code @Transactional}-annotated methods in {@code
 * ClaimRedemptionServiceTest}/{@code NoDisclosureContentInLogsTest}) deliberately wrap the whole
 * test method in one ambient transaction and then use a bare {@code jdbc} call to read back an
 * uncommitted JPA write made earlier in that same method — {@code REQUIRES_NEW} would suspend that
 * ambient transaction and open a separate physical connection unable to see the not-yet-committed
 * row at all (a real regression this config introduced and {@code REQUIRED} fixes: joining the
 * existing transaction shares its connection and therefore its uncommitted writes, exactly like a
 * bare {@code jdbc} call always behaved before RLS made this config necessary in the first place).
 */
@TestConfiguration
public class TransactionalTestJdbcTemplateConfig {

  @Bean
  @Primary
  JdbcTemplate transactionalJdbcTemplate(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    JdbcTemplate target = new JdbcTemplate(dataSource);

    DefaultTransactionAttribute required = new DefaultTransactionAttribute();
    required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    NameMatchTransactionAttributeSource attributeSource = new NameMatchTransactionAttributeSource();
    attributeSource.addTransactionalMethod("*", required);

    ProxyFactory proxyFactory = new ProxyFactory(target);
    proxyFactory.setProxyTargetClass(true);
    proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, attributeSource));

    return (JdbcTemplate) proxyFactory.getProxy();
  }
}
