# shared/audit — the `audit_log` write path (KH-0.6b)

The single, transactional write path into `audit_log` (spec FS-0.6b D8, SEC §9.4/§9.7, NFR-08).
A sub-package of the `shared` module (not a separate Modulith module), exposed as the `audit`
named interface.

## Exposed

- `AuditService#record(AuditAction, entityType, entityRef, detail)` — the only way to write an
  `audit_log` row. `@Transactional` (`REQUIRED` propagation), so a call from inside an
  already-transactional method (issuing a credential, logging in, rotating a key) commits or
  rolls back atomically with that operation — never an event without its audit row.
- `AuditAction` — the closed catalog of event types (spec FS-0.6b §6).
- `AuditPrincipal` — the SPI `rbac`'s Spring Security principal types implement so the actor
  (`USER` / `API_KEY`, with id) can be inferred from `SecurityContextHolder` without `shared`
  depending on `rbac`. No current `Authentication`, or a principal that doesn't implement this
  interface (a scheduled worker task, a startup bootstrap runner), attributes the row to `SYSTEM`.

## Not exposed

`AuditLogEntry` (the JPA entity) and `AuditLogRepository` are package-private — this package's own
implementation detail. Nothing outside `shared.audit` should ever need them.

## Migrated from direct inserts (KH-0.6b)

- `key.domain.KeyLifecycleService` — `KEY_CREATED` / `KEY_ROTATED` (previously a raw
  `JdbcTemplate` insert, KH-0.5's stopgap).
- `credential.worker.ClaimCodeExpiryWorker` — `CLAIM_CODES_EXPIRED` (previously a raw
  `JdbcTemplate` insert, ADR-09-worker's stopgap).

An architectural test (`NoDirectAuditLogInsertTest`) fails the build if any new direct
`INSERT INTO audit_log` appears outside this package (DoD-7).

## New in KH-0.6b

`credential.domain.CredentialService` now records `CREDENTIAL_ISSUED` / `CREDENTIAL_CONSUMED` /
`CREDENTIAL_REVOKED`; `rbac` records the seven `AUTH_*` / `API_KEY_*` / `USER_CREATED` actions (spec
FS-0.6b §6).
