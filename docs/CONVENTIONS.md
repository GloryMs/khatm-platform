# CONVENTIONS — khatm-platform (Java / Spring)
> One concept = one style. If you find code violating this file, migrate the file you touch.
> Tooling enforces most of this: Spotless (google-java-format) + Checkstyle + Modulith test.

## 1. Project layout
```
src/main/java/sy/khatm/platform/
├─ <module>/                    # tenant, key, schema, credential, status, ledger,
│  ├─ package-info.java         # holder, consumer, rbac, connector
│  ├─ api/                      # exposed interfaces + DTO records (the ONLY cross-module surface)
│  ├─ domain/                   # entities + domain services (module-private)
│  ├─ persistence/              # Spring Data repositories (module-private)
│  ├─ web/                      # REST controllers of this module
│  └─ events/                   # published event records
└─ shared/                      # tenant context, error envelope, audit, i18n config
src/main/resources/
├─ db/migration/                # Flyway: V<version>__<snake_case_description>.sql
└─ i18n/messages_en.properties + messages_ar.properties
```

## 2. Naming
- Entities: singular noun (`Credential`). Tables: snake_case singular (`credential`).
- DTO records: suffix by direction — `IssueRequest`, `CredentialResponse`.
- Services: `<Concept>Service`; mappers: `<Concept>Mapper`; events: past tense (`CredentialIssued`).
- Error codes: `KH-<MOD>-<NNNN>`; module tags: TEN, KEY, SCH, CRD, STS, LDG, HLD, CNS, RBC, CON, SYS.
- REST: `/api/v1/<plural-resource>`; path params are opaque refs, never DB ids.
- Checkstyle exceptions (documented here, not just in `checkstyle.xml`): the `ConstantName`
  rule permits `log`/`logger` in addition to `UPPER_SNAKE_CASE` (logger fields are mutable
  state, not true constants — Google Java Style Guide §5.2.4); the `MethodName` rule permits
  underscore-separated segments after the initial lowerCamelCase start, so the test naming
  convention in §8 (`methodName_condition_expectedResult`) is actually enforceable.

## 3. The i18n pattern (rule 2) — exactly this, everywhere
- **Stored display names** → JSONB column `name_i18n` mapped to `LocalizedText` record
  (`Map<String,String>` with `en` mandatory, `ar` mandatory for tenant-facing rows).
  Reads resolve by request locale with `en` fallback. One shared JPA converter — do not
  re-implement per entity.
- **Generated messages** (errors, statuses) → `MessageSource` keys, dot notation:
  `credential.not-found`, `key.rotation.in-progress`. Key exists in BOTH bundles or CI fails
  (`MessageBundleParityTest`).
- Locale from `Accept-Language` (`en` default). Never hardcode user-facing English strings
  in Java code — no exceptions.

## 4. Error handling pattern (rule 3) — exactly this, everywhere
```java
throw new NotFoundException(ErrorCode.KH_CRD_0404, "credential.not-found", ref);
```
- Hierarchy: `KhatmException` → NotFound / Conflict / Validation / Authentication /
  Authorization / Integrity. Constructor takes (ErrorCode, messageKey, args...).
- `GlobalExceptionHandler` (@RestControllerAdvice) is the ONLY place producing the envelope;
  controllers/services never build error responses.
- Unexpected exceptions → `KH-SYS-0500`, generic localized message, full stack trace to log
  with traceId, nothing internal to the client.
- Validation: Bean Validation on request records; handler folds violations into
  `details[]` with per-field messageKeys.
- Logging (JSON): `log.info("credential issued ref={} schema={}", ref, schemaCode)` —
  placeholders, no string concat; no claims/JWT/keys/PII ever (SEC §9).

## 5. Persistence
- Repositories extend `JpaRepository`; custom queries with `@Query` JPQL; native SQL only
  for the atomic consume UPDATE and RLS-adjacent operations — each documented with WHY.
- Transactions at service layer (`@Transactional`), never controllers/repositories.
- Optimistic locking (`@Version`) on mutable aggregates; the consume path relies on the
  conditional UPDATE, not on versions.
- Pagination mandatory on list endpoints (`Pageable`, max 200).
- JPA entities are `public` classes with `public` accessors — Java visibility alone cannot
  express Modulith module-privacy. Keeping an entity out of another module's reach is
  `ModulithBoundariesTest`'s job (it lives in `domain/`, a module-private sub-package), not
  the entity's own access modifiers.

## 6. Migrations are append-only

CLAUDE.md "Database rules": *"Flyway is the ONLY source of schema... Never edit an applied
migration — append a new one."* KH-0.2.2 makes this build-enforced, not just a convention:

- **Local/build-time layer**: `db/migration-checksums.lock` (repo root — not under
  `src/main/resources`, since it has no runtime purpose and shouldn't ship in the JAR) records
  `<filename>\t<sha256>` for every file in `src/main/resources/db/migration/`.
  `MigrationImmutabilityTest` recomputes every checksum on every build and fails on: a
  mismatch (the file was edited), a locked file that's gone (deleted), or a migration file
  with no lock entry (added without registering it).
- **Adding a NEW migration**: create `V<N>__description.sql`, run the build once — the failing
  test prints the exact line to add (filename + freshly computed checksum). Paste it into
  `db/migration-checksums.lock` and re-run. Never hand-edit an *existing* line; that's the
  violation this file exists to catch.
- **Runtime layer**: `spring.flyway.validate-on-migrate: true` (`application.yml`) — Flyway's
  own default, made explicit — catches the same class of drift at startup against whatever a
  real database's `flyway_schema_history` actually recorded, independent of the build-time
  check.
- **CI-prep**: `scripts/check-migration-checksums.sh` is a standalone, JVM-free
  re-implementation of the same three checks, meant to be dropped into KH-0.3.1's pipeline as
  an early step. Keep it in sync with `MigrationImmutabilityTest` if the policy changes.
- **`.gitattributes` pins `*.sql`/`*.sh`/`*.lock` to `eol=lf`**: without this, a Windows clone
  (`core.autocrlf=true`) and a Linux CI runner can end up with different line endings for the
  "same" file, which would make the checksum check fail spuriously on the first CI run for
  reasons that have nothing to do with an actual edit.

## 7. Async pattern (ADR-09) — exactly this
- Publish Modulith application event (record) inside the transaction; externalization →
  Redis Streams via outbox. Workers consume with consumer groups; handlers idempotent
  (keyed on event id). No `@Async` for anything that must survive a crash.
- The `event_publication` table in `V1__baseline.sql` is Spring Modulith's own schema,
  copied verbatim from `spring-modulith-events-jdbc`. Upgrading `spring-modulith.version`
  requires diffing the library's official `schema-postgresql.sql` for that version against
  our migration — never hand-edit the table to "keep up."

## 8. Tests
- Unit: JUnit 5 + Mockito, no Spring context where avoidable.
- Integration: `@SpringBootTest` + Testcontainers (Postgres+Redis) per module slice.
- Mandatory named tests: `ModulithBoundariesTest`, `MessageBundleParityTest`,
  `ConcurrentConsumeTest` (50 parallel, exactly 1 success), `MigrationCleanBootTest`,
  `MigrationImmutabilityTest` (checksum-locks every applied migration — KH-0.2.2, §6).
- Naming: `methodName_condition_expectedResult`.

## 9. Documentation (rule 1)
- `package-info.java`: module purpose, exposed API, events published/consumed, tables owned.
- Javadoc on exposed API: first sentence = what; body = why/invariants; `@throws` for
  every KhatmException subtype.
- OpenAPI annotations complete on every endpoint incl. error envelope examples.
- `docs/error-codes.md` regenerated from `ErrorCode` enum by a test — never hand-edited.
- Approved specs live in `docs/specs/` only — copied there from khatm-docs at implementation
  start. No other spec locations.

## 10. Commits & PRs
- Conventional commits: `feat(credential): KH-1.4.1 persistent idempotency`.
- One WBS task per PR; PR description links spec + lists DoD checklist.
- A PR introducing or modifying core invariant logic (atomic consume, idempotency, key
  signing, status-list allocation) MUST include its concurrency/correctness test in the
  same PR. No downstream-PR exceptions after 2026-07-13.
- CI must be green before merge. `.github/workflows/ci.yml` (KH-0.3.1) runs the migration
  checksum guard, then `mvn verify` (Spotless, Checkstyle, Modulith boundaries, all tests) on
  every PR into `main` and every push to `main` — a red run blocks merging, no exceptions.
