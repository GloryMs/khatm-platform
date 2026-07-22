# shared

Cross-cutting infrastructure used by every other module: web configuration (CORS, locale
resolution), the single error-handling vocabulary and hierarchy, the sole error-envelope
producer, structured logging, the `name_i18n` / `label_i18n` JSONB convention (`LocalizedText`,
`LocalizedTextConverter`), UUIDv7 id generation (`Uuidv7`), provisional single-tenant context
(`TenantContext`), OpenAPI configuration. Has no outbound dependencies on other Khatm modules.

**Events in:** none. **Events out:** none.

**Tables owned:** `audit_log` (append-only, created by KH-0.2.1; the write path is KH-0.6b).

**Status (KH-0.6a, spec FS-0.6a): CLAUDE.md work rules 2 & 3 are now LIVE.**

- **`error/`** (`@NamedInterface("error")`) — `KhatmException` (abstract; constructor
  `(ErrorCode, messageKey, Object... args)`) and its six subtypes: `NotFoundException`,
  `ConflictException`, `ValidationException`, `IntegrityException` are thrown today;
  `AuthenticationException`/`AuthorizationException` exist but stay unthrown until KH-0.6b
  (session/API-key auth, RBAC). `ErrorCode` is the `KH-<MOD>-<NNNN>` registry — deliberately a
  *lean first batch* (`KH-CRD-0404`, `KH-KEY-0500`, `KH-SYS-0400`, `KH-SYS-0500`) covering only
  request-error paths that actually exist; new codes are appended, never renumbered, never added
  speculatively ahead of the path that needs them. `VerifyReason` is the separate, non-exception
  vocabulary for credential-verification domain results (a verification failure is a 200 result,
  never a thrown exception — spec FS-0.6a D1).
- **`web/`** (`@NamedInterface("web")`, exposing only `ErrorEnvelope`) — `GlobalExceptionHandler`
  is the *only* place in the platform that turns an exception into an HTTP response: every
  `KhatmException`, Bean Validation's `MethodArgumentNotValidException` (→ `details[]` with
  `validation.<constraint>` keys), and a catch-all `Exception` → `KH-SYS-0500` (generic message,
  full stack trace logged, nothing internal reaches the client). `TraceIdFilter` stamps every
  request with a trace id (inbound `X-Request-Id` if present, else a generated UUID) via MDC,
  echoed in the response header and the envelope.
- **i18n** (`config/LocaleConfig`, module-private) — `Accept-Language` only, `en` default,
  `en`/`ar` supported, anything else silently falls back to `en` (no error). `MessageSource` is
  explicitly UTF-8 (`.properties` defaults to ISO-8859-1, which would silently corrupt Arabic).
  Bundles: `src/main/resources/i18n/messages_{en,ar}.properties` — `MessageBundleParityTest`
  fails the build on any key mismatch, blank value, or missing `ErrorCode`/`VerifyReason` key.
- **Logging** (`logback-spring.xml`, repo root `src/main/resources/`) — JSON
  (`logstash-logback-encoder`) in every profile except `local` (human-readable console there);
  `traceId` rides on every line via MDC. Log messages themselves are always English
  (CONVENTIONS.md §4) regardless of request locale.
- **`docs/error-codes.md`** is generated from the `ErrorCode` enum by
  `ErrorCodesDocGenerationTest` — never hand-edited (CLAUDE.md work rule 1).

**Stats/counters (KH-1.1.3):** `web.StatsController` serves `GET /api/v1/stats` — the console's
C4 pilot-metrics dashboard — as a `GROUP BY action` read over `audit_log` via
`AuditService#countActionsInWindow` (new), session-gated (`ScopeGuard#requireUserSession`, same
as credential search). `audit_log_tenant_occurred_idx` (`V6`) backs the `(tenant_id,
occurred_at)` range scan the aggregation performs. `AuditAction` gained
`CREDENTIALS_BULK_ISSUED`/`CREDENTIAL_VERIFY_OK`/`CREDENTIAL_VERIFY_FAILED` (written by
`credential`, not this module) so the dashboard has real counters for bulk issuance and online
verification outcomes, which had no audit trail before this session.

**For future sessions:** adding a new user-facing string or throw site now means extending
`ErrorCode`/`VerifyReason` and *both* message bundles in the same commit — `MessageBundleParityTest`
and `ErrorCodesDocGenerationTest` fail the build otherwise. This is deliberate (spec FS-0.6a): the
whole point of this session was to make work rules 2 & 3 impossible to accidentally skip going
forward.
