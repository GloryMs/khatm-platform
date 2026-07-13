# STATE — khatm-platform
> Updated at the end of EVERY Claude Code session. This file is the session anchor.

## Current phase / task
- Phase 0 — Production Foundation
- Active task: KH-0.2.1 (Flyway V1__baseline enterprise schema) — NOT STARTED
- Branch ready for review: `feat/KH-0.1.1-modulith-structure`

## Last completed
- 2026-07-13: KH-0.1.1 + KH-0.1.2 — Modulith restructure + boundary verification
  - Package layout migrated from `sy.khatm.poc.*` → `sy.khatm.platform.*`
  - All 11 modules created with `package-info.java` (tenant, key, schema, credential,
    status, ledger, holder, consumer, rbac, connector, shared)
  - Spring Modulith 1.2.4 BOM + `spring-modulith-starter-core` + `spring-modulith-starter-test`
  - `ModulithBoundariesTest` (pure bytecode analysis, no DB) — GREEN
  - Spotless 2.43.0 (google-java-format 1.22) — GREEN
  - Checkstyle 3.3.1 — GREEN (custom `checkstyle.xml`)
  - `.editorconfig` added
  - `pom.xml` renamed to `khatm-platform`, Spring Boot 3.3.4 retained

## Decisions made this session
- **Cross-module key access**: `credential` module depends on `key :: api` (the `KeySigner`
  interface in `key/api/`). `SoftKeyService` in `key/domain/` is module-private.
  Named-interface mechanism: `@NamedInterface("api")` on `key/api/package-info.java`.
- **`credential/api/` named interface**: `@NamedInterface("api")` exposes only DTO records.
  `Credential` entity, `CredentialService`, repositories are module-private (in sub-packages).
- **DemoSeeder placement**: inside `credential/seed/` module, `@Profile({"local","dev"})`.
  Depends directly on `CredentialService` (same module — no API interface needed).
- **`ddl-auto: update` kept**: Flyway is not yet configured; KH-0.2.1 will switch to
  `validate` + add V1__baseline migration.
- **Entity visibility**: JPA entities are `public` class + `public` accessors. Modulith
  boundary enforcement (via `verify()`) prevents external modules from using them — Java
  visibility alone cannot express Modulith-level module privacy.
- **Checkstyle logger convention**: `ConstantName` rule extended to allow `log`/`logger`
  (not true constants per Google Style Guide §5.2.4; mutable logging state).

## Environment facts
- Local: Windows + IntelliJ + Docker Desktop. Shared network `khatm-net` created.
- DB exposed on :5432 for IntelliJ; API on :8080.
- Maven 3.9.9 / Java 17 (must export PATH manually: `export PATH="$MAVEN_HOME/bin:$PATH"`).
- Default tenant strategy: single default tenant row until KH-2.1.

## Open decisions / blockers
- (none)

## Next up (ordered)
1. KH-0.2.1 Flyway V1__baseline (enterprise schema incl. tenant_id + name_i18n)
   → spec: `specs/FS-0.2-database-baseline.md` — approved, ready to implement
2. KH-0.2.2 ddl-auto=validate + migration CI check
3. KH-0.5 KeyProvider SPI (SoftKeyProvider, kid in JWS) — replaces ephemeral SoftKeyService
4. KH-0.4 SD-JWT signing upgrade
5. KH-0.6 Console auth + API-key filter + audit_log
