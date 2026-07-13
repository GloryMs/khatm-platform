package sy.khatm.platform.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import sy.khatm.platform.support.IntegrationTestSupport;

/**
 * FS-0.2 §5 acceptance criterion 6 — inserting a {@code tenant} row whose {@code name_i18n} is
 * missing the {@code ar} key fails on the {@code tenant_name_i18n_langs} CHECK constraint (work
 * rule 2: EN/AR everywhere).
 */
class TenantNameI18nCheckTest extends IntegrationTestSupport {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void insert_tenantWithNameI18nMissingArabic_violatesCheckConstraint() {
    UUID id = UUID.randomUUID();
    String slug = "check-probe-" + id;

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant (id, slug, name_i18n, type, deploy_mode, status)
                    VALUES (?, ?, ?::jsonb, 'OTHER', 'SAAS', 'ACTIVE')
                    """,
                    id,
                    slug,
                    "{\"en\":\"English only\"}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("tenant_name_i18n_langs");
  }
}
