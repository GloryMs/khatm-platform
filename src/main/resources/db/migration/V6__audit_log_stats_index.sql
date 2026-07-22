-- KH-1.1.3-BE: stats/counters endpoint (GET /api/v1/stats).
--
-- AuditLogRepository#countByActionInWindow scans audit_log filtered by (tenant_id, occurred_at)
-- and grouped by action on every call — audit_log is append-only and grows with every business
-- event the platform records (KH-0.6b onward), so this range-scan-then-group query genuinely
-- needs an index of its own; the table's only prior index was its bigint identity primary key.
CREATE INDEX audit_log_tenant_occurred_idx ON audit_log (tenant_id, occurred_at);
