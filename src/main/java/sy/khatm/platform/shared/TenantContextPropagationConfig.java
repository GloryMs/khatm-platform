package sy.khatm.platform.shared;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Wires {@link TenantContextTransactionExecutionListener} onto the auto-configured {@link
 * JpaTransactionManager} (spec FS-2.1 D4) — a {@link BeanPostProcessor} rather than a named-bean
 * lookup, since it needs no dependency on Boot autoconfiguration's internal bean name and works
 * identically whether one or several {@code JpaTransactionManager} beans exist.
 */
@Configuration
class TenantContextPropagationConfig {

  @Bean
  static BeanPostProcessor tenantContextTransactionListenerRegistrar() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JpaTransactionManager transactionManager) {
          List<TransactionExecutionListener> listeners =
              new ArrayList<>(transactionManager.getTransactionExecutionListeners());
          listeners.add(
              new TenantContextTransactionExecutionListener(transactionManager.getDataSource()));
          transactionManager.setTransactionExecutionListeners(listeners);
        }
        return bean;
      }
    };
  }
}
