package sy.khatm.platform.shared.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link AuditLogEntry} rows.
 *
 * <p>Package-private — only {@link AuditService} may write through this; {@code audit_log} itself
 * additionally rejects {@code UPDATE}/{@code DELETE} at the database level (append-only trigger,
 * {@code AuditLogAppendOnlyTest}), so this interface only ever needs {@code save}.
 */
interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {}
