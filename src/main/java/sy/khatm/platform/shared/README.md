# shared

Cross-cutting infrastructure used by every other module: web configuration (CORS, locale
resolution), the `name_i18n` / `label_i18n` JSONB convention (`LocalizedText`,
`LocalizedTextConverter`), UUIDv7 id generation (`Uuidv7`), provisional single-tenant context
(`TenantContext`), OpenAPI configuration. Has no outbound dependencies on other Khatm modules.

**Events in:** none. **Events out:** none.

**Tables owned:** `audit_log` (append-only, created by KH-0.2.1; the write path arrives with
KH-0.6 / KH-1.6.3's error-handling and audit work).

**Status:** the error envelope hierarchy (`KhatmException` + `ErrorCode` registry,
`@RestControllerAdvice`) described in CLAUDE.md work rule 3 has not been built yet — deferred
to a dedicated task, not part of KH-0.2.1.
