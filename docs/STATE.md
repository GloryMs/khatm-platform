# STATE — khatm-platform
> Updated at the end of EVERY Claude Code session. This file is the session anchor.

## Current phase / task
- Phase 0 — Production Foundation
- Last task: KH-0.4.1 + KH-0.4.2 + KH-0.4.3 (SD-JWT signing upgrade) — DONE, `mvn verify`
  green (37/37 tests, Spotless/Checkstyle/Modulith boundaries clean), CI green. KH-0.4.4
  (wallet UI) is out of scope — different repo.
- PR #8 (`feat/KH-0.4-sdjwt-upgrade` → `main`) merged 2026-07-16; branch deleted.
- PR #6/#7 (docs ratifications + STATE.md follow-up) merged 2026-07-15; branches deleted.
- PR #5 (`feat/KH-0.5-key-provider-spi` → `main`) merged 2026-07-15; branch deleted.
- PR #4 (KH-0.3.1, CI pipeline) merged 2026-07-14 (commit `4a65a39`); branch deleted.
- `main` is current HEAD for the next session to branch from.
- Branch protection is enabled on this repo — all changes (including docs-only housekeeping)
  go through a PR, never a direct push to `main`.

## Last completed
- 2026-07-16: KH-0.4.1 + KH-0.4.2 + KH-0.4.3 — SD-JWT signing upgrade (spec FS-0.4, all eight
  pre-approved design decisions D1–D8 implemented as given).
  - **Library confirmed before adopting (D4 gate)**: `com.authlete:sd-jwt:1.9` — read its
    actual source on GitHub (not just the README) to verify it never touches signing/key
    material. Confirmed: `SDObjectBuilder`/`Disclosure`/`SDJWT` only build/parse the payload
    Map and disclosure strings; signing stays exclusively through `KeySigner` (unchanged, D4).
    Its own `_sd` digest-list builder already sorts alphanumerically — satisfies D5's
    "(shuffled)" requirement for free, no manual shuffling needed. Its default `Disclosure`
    salt generation is already `SecureRandom`, 128-bit, base64url — satisfies D5's salt
    requirement exactly, verified by reading `SDUtility.generateRandomBytes`/`Disclosure`
    source directly rather than trusting the README's word for it.
  - **`CredentialService#issue`**: every `claims` entry → `SDObjectBuilder.putSDClaim` (D1);
    only D3's structural fields (`iss`, `iat`, `nbf`, `exp`, `vct` = `{schema.code}:{version}`,
    `ref`, `status`) added via `putClaim`; `status` follows the IETF Token Status List shape
    (`status.status_list.{idx,uri}` — `uri` is a provisional placeholder, the raw
    `status_list_id`, until KH-1.3 publishes a real signed-bitstring endpoint, spec §7).
    `JWTClaimsSet.parse(Map)` converts the built payload Map into what `KeySigner.sign()`
    still takes unchanged. `credential.signed_payload` stores the compact JWT only (D6); the
    response returns the full tilde-separated presentation (`IssueResponse.sdJwt`, replacing
    the old `jwt` field per work rule 4 — not kept alongside it).
  - **`CredentialService#verify`**: accepts the tilde presentation, or a bare compact JWT
    treated as a zero-disclosure presentation (spec §5 — not an error). Existing sig/exp/ref
    /revoked checks unchanged; added `_sd_alg == "sha-256"` check, per-disclosure digest +
    duplicate-name checks (`forged_disclosure`/`duplicate_disclosure`), and the D2
    mandatory-disclosure check (every `claims_def` field not in `sd_fields` must be
    disclosed, else `withheld_mandatory_claim`) via `SchemaCatalog#findById` — no new
    cross-module dependency, `schema :: api` was already depended on.
  - **`ClaimsEncryptionService`** (new, `credential.domain`, module-private): AES-256-GCM, key
    from `khatm.claims.enc-key` (32-byte base64 env var), random 96-bit nonce per call
    prepended to the ciphertext, fail-fast startup outside `local` — mirrors
    `SoftKeyProvider`'s passphrase pattern exactly (constructor check + `application.yml`
    profile document + dedicated fail-fast test). `CredentialService#issueClaimCode` now
    actually encrypts `join(disclosures, "~")` into `disclosures_enc` (D7) — closes the
    encryption half of the long-open `disclosures_enc` blocker (see below).
  - **`schema :: api` widened**: `SchemaRef` gained `claimsDefJson` + `sdFields` (previously
    id/code/version only) — the verify path's mandatory-disclosure check needs the full
    field list. No new module boundary; `credential` already depended on `schema :: api`.
  - **`DemoSeeder`**: demo schema now has a real mandatory/optional split (`result` mandatory;
    `caseNumber`/`issuedAt` withholdable) so both directions are exercised by construction.
  - **OpenAPI**: `springdoc-openapi-starter-webmvc-api:2.8.17` added (the last release in the
    2.x line — matches Spring Boot 3.3.x; the 3.x line targets Boot 3.4+). Deliberately the
    "-api" artifact, not "-webmvc-ui" — JSON generation (`/v3/api-docs`) and annotations only,
    no live Swagger UI exposed (no auth exists ahead of KH-0.6). Only `/issue` and `/verify`
    annotated (this session's actual scope); full endpoint coverage and CI-published
    `openapi.json` remain KH-1.6 (spec FS-0.4 §7 names it explicitly).
  - **Message bundles**: still don't exist (`messages_en/ar.properties` — KH-0.6, unchanged
    from KH-0.5's note). No new user-facing message keys were introduced this session either,
    so there was nothing to add even if the bundles existed.
  - **Tests**: `SdJwtIssuanceStructuralTest` (DoD #1, flagship — decodes the *persisted*
    `signed_payload` row and asserts no `claims_def` key/value appears anywhere, only D3
    fields + `_sd`/`_sd_alg`), `SdJwtVerificationTest` (DoD #2 round-trip, #3 selective
    disclosure, #4's four rejections — tampered value, forged digest, duplicate, withheld
    mandatory — plus the zero-disclosure-presentation case), `ClaimsEncryptionServiceTest` +
    `ClaimsEncryptionKeyFailureTest` (DoD #5), `NoDisclosureContentInLogsTest` (DoD #7 — a
    Logback `ListAppender` captures a full issue→verify→claim-code cycle and asserts no
    plaintext claim value or salt appears in any log line). DoD #6 (FS-0.5 key-module tests
    unmodified and green) confirmed by running that suite untouched — all 23 pass.
- 2026-07-15: KH-0.5.1 + KH-0.5.2 + KH-0.5.3 — Key Provider SPI & SoftKeyProvider (spec
  FS-0.5, all four pre-approved design decisions D1–D4 implemented as given).
  - **`key :: api`** unchanged surface, new shape: `KeySigner.sign()` now returns `SignResult`
    (`kid`/`algo`/`jws`) instead of a bare `String`; new `KeyVerifier.resolvePublicKey(kid)` →
    `Optional<PublicKeyHandle>`, resolving strictly by `kid` with no fallback (SEC §3, spec §4).
  - **`key/domain/`** (all module-private): `KeyProvider` — a deliberately crypto-only SPI
    (`generate`/`sign`/`publicKey` against an opaque `providerRef`), scoped this way (not the
    tenant/DB-aware "full SEC §3 contract" shape literally) so a future `KmsProvider` never
    needs to know about `issuer_key` rows or lifecycle states (D3). `SoftKeyProvider` — the only
    implementation today: one PKCS#12 keystore file, alias == `kid`, selected via
    `@ConditionalOnProperty(khatm.keys.provider=SOFT, matchIfMissing=true)`. Mints a throwaway
    self-signed X.509 cert per key (via `bcpkix-jdk18on`, new `pom.xml` dependency) purely to
    satisfy `KeyStore.setKeyEntry`'s chain requirement — verification never uses the cert chain,
    only the raw EC public key. `KeyLifecycleService` — owns `issuer_key` persistence, the
    `PENDING→ACTIVE→RETIRING→RETIRED` state machine, and the one-`ACTIVE` invariant; `rotate()`
    is fully implemented (no REST endpoint — tests only, per spec) and writes `KEY_CREATED` /
    `KEY_ROTATED` `audit_log` rows via a direct `JdbcTemplate` insert (minimal form; full audit
    write path is KH-0.6). `KeyBootstrap` — idempotent `ApplicationRunner`, all profiles.
  - **`key/web/JwksController`** replaces the old `WellKnownController`:
    `GET /.well-known/jwks.json` only (the old `/.well-known/pubkey.pem` endpoint is gone — not
    in FS-0.5's scope, and the old `KeySigner.publicKeyPem()` method it depended on no longer
    exists), `ACTIVE`+`RETIRING` public keys, `Cache-Control: max-age=300`, no auth.
  - **`SoftKeyService` deleted in full** (D4 — no `@Deprecated` shim); `CredentialService`
    rewired to the new `KeySigner`/`KeyVerifier` contracts (added `KeyVerifier` constructor
    dependency; `verify()` now resolves the JWT's `kid` header through it and checks the
    signature manually with a Nimbus `ECDSAVerifier` — no other module needed to change).
  - **`shared.TenantContext`** gained `DEFAULT_TENANT_SLUG`/`currentSlug()` (mirrors the
    existing `DEFAULT_TENANT_ID`/`current()` pair) — lets `key` build `kid` values
    (`{tenant-slug}:key-{seq}`) without a cross-module dependency on `tenant`, which has no
    `api` sub-package yet.
  - **`IssuerKeyRepository.retireActive`**: a `@Modifying` JPQL bulk `UPDATE` (not a plain
    entity save) — deliberately runs immediately rather than being deferred to Hibernate's
    flush-time ordering (which flushes pending inserts before pending updates), so `rotate()`'s
    old-key-to-`RETIRING` transition is guaranteed to commit at the database *before* the new
    key is inserted as `ACTIVE`. Without this, the `issuer_key_one_active` partial unique index
    could see two `ACTIVE` rows momentarily and reject the insert.
  - **`application.yml`**: new `khatm.keys.*` surface per spec §7. The base document leaves
    `khatm.keys.soft.passphrase` with no default (`${KHATM_KEYS_PASSPHRASE:}`); a second
    `spring.config.activate.on-profile: local` document supplies the only permitted default.
    `SoftKeyProvider`'s constructor fails startup immediately if the passphrase is blank and the
    `local` profile isn't active — verified by test, not just by inspection.
  - **`docker-compose.yml`**: named volume `khatm_keys` mounted at `/var/khatm/keys` on both
    `khatm-api` and `khatm-worker` (same file, both roles), plus `KHATM_KEYS_PASSPHRASE` env
    (same local-only default as `application.yml`'s `local` profile document). `khatm-deploy`
    (separate repo) intentionally untouched.
  - **Tests** (`src/test/java/sy/khatm/platform/key/**`, plus one in `credential/domain/`):
    `KeyLifecycleServiceTest` (bootstrap idempotency, `rotate()`'s one-active invariant +
    JWKS-shows-both + old-signature-still-verifies + new-kid, unknown/`RETIRED` kid rejection,
    no private material in `public_jwk`, both audit rows): 7 tests, all against the shared
    Testcontainers context. `KeyProviderRestartPersistenceTest` — the criterion-2 test: two
    fully independent `SpringApplicationBuilder` runs (own dedicated Postgres container, real
    `.run()`/`.close()` cycle) against the *same* keystore file, proving a signature from the
    first run still verifies under the same `kid` after the second. `SoftKeyProviderPassphraseFailureTest`
    — wrong passphrase on an existing keystore, and missing passphrase outside `local`, both
    fail startup with a message traceable to "passphrase," and the file is never overwritten.
    `JwksControllerTest` — plain Mockito unit test (no Spring context) for the HTTP response
    shape/headers. `CredentialSigningAndVerificationTest` — `kid` format through the real
    issuance path, and a JWT signed by a key outside the registry rejected as `bad_signature`.
  - **`IntegrationTestSupport`** (shared test base) gained its own `khatm.keys.soft.*`
    `@DynamicPropertySource` (one temp keystore file for the whole shared-context suite) —
    every pre-existing integration test runs under the `test` profile, not `local`, so without
    this every one of them would have failed `SoftKeyProvider`'s new fail-fast passphrase check.
  - **Toolchain note**: this session's `mvn verify` required `JAVA_HOME` pointed at the
    Eclipse Temurin 21 install (`environment.md` memory had drifted back to JDK 17 — fixed).
- 2026-07-15: Housekeeping — spec-directory reconciliation. Root `specs/` was a manual-copy
  mistake; `docs/specs/` is the canonical location per CLAUDE.md. Both `FS-0.2` and `FS-0.5`
  were byte-identical in both locations, so the root copies were `git mv`-removed and the
  now-empty `specs/` directory deleted; `docs/CONVENTIONS.md` §9 gained a line stating
  `docs/specs/` is the only approved spec location.
- 2026-07-14: KH-0.3.1 — GitHub Actions CI pipeline
  - `.github/workflows/ci.yml`: triggers on `pull_request` into `main` and `push` to `main`.
    Fail-fast step order: `scripts/check-migration-checksums.sh` (cheap, no JVM) →
    `actions/setup-java@v4` (Temurin JDK 21, `cache: maven`) → `mvn -B verify` (Spotless,
    Checkstyle, Modulith boundaries, all tests). No deploy steps (KH-0.3.3 is out of scope).
  - **Confirmed the `src/test/resources/docker-java.properties` (`api.version=1.44`) pin from
    KH-0.2.1 does NOT break `ubuntu-latest` runners** — Testcontainers-backed tests passed in
    CI on the first run with no changes needed. That pin was specifically for local Docker
    Desktop/Windows quirks; GitHub-hosted runners' native Docker Engine negotiates the pinned
    API version fine. No conditional logic added — none was needed.
  - First CI run on this task's own PR (#4) went green end-to-end in 1m31s — verified via
    `gh run watch` and `gh pr checks`, not just "should work."
  - Minimal repo-root `README.md` added (one paragraph + CI badge); full README deferred.
  - `docs/CONVENTIONS.md` §10 gains "CI must be green before merge."
  - `.gitattributes`: extended `eol=lf` pinning to `*.yml`/`*.yaml` (same rationale as
    KH-0.2.2's `*.sql`/`*.sh`/`*.lock` rule) and normalized the two YAML files that had
    drifted to CRLF in the working tree.
- 2026-07-14: KH-0.2.2 — append-only migration discipline
  - **Local/build-time guard**: `db/migration-checksums.lock` (repo root; `<filename>\t<sha256>`
    per line) + `MigrationImmutabilityTest`
    (`src/test/java/sy/khatm/platform/db/MigrationImmutabilityTest.java`, no Spring context —
    pure file I/O, stays fast). Recomputes every migration's SHA-256 on every build; fails on
    a checksum mismatch (edited), a locked file that's gone (deleted), or a migration file
    with no lock entry (added without registering it). The UNREGISTERED failure message
    prints the exact line to paste into the lock file. All three failure paths manually
    verified by temporarily corrupting the lock file / adding an unregistered file / removing
    a locked file and confirming the expected message, then restoring.
  - **`.gitattributes` added** (`*.sql`, `*.sh`, `*.lock` → `eol=lf`): the migration file was
    CRLF in the working tree (Windows `core.autocrlf=true`) while the git blob was already LF
    — without pinning this, a future Linux CI checkout would see different bytes than this
    Windows session hashed, and the very first CI run would falsely report `V1__baseline.sql`
    as "modified." Renormalized `V1__baseline.sql` to LF on disk to match.
  - **CI-prep layer**: `scripts/check-migration-checksums.sh` — standalone bash
    re-implementation of the same three checks (no JVM needed), executable bit tracked in git
    (`100755`), ready for KH-0.3.1 to invoke as a pipeline step. GitHub Actions workflow itself
    is explicitly KH-0.3.1's scope, not built here.
  - **Runtime layer**: `spring.flyway.validate-on-migrate: true` made explicit in
    `application.yml` (was already Flyway's default) — catches drift against a real
    database's `flyway_schema_history`, independent of the build-time checksum check.
  - `docs/CONVENTIONS.md` gains `## 6. Migrations are append-only`; sections 6–9 renumbered to
    7–10 to make room (Async, Tests, Documentation, Commits & PRs), including a `§7`→`§8`
    cross-reference fix. `MigrationImmutabilityTest` added to §8's mandatory named tests list.
- 2026-07-14: Housekeeping (approved architecture-review decisions, no WBS feature work)
  - Rebased `feat/KH-0.2.1-baseline-schema` onto updated `main` (KH-0.1.1 merged via PR #1).
    Trivial — git recognized `3713499` was already incorporated as squash commit `dfde818`
    and skipped it, replaying only the KH-0.2.1 commit. Zero conflicts.
  - Upgraded toolchain to Java 21: `pom.xml` `java.version` + `maven.compiler.release` both
    `21`. Installed Eclipse Temurin 21.0.11 (`winget install EclipseAdoptium.Temurin.21.JDK`).
    Verified with `mvn clean verify` under `JAVA_HOME` pointed at the new JDK: BUILD SUCCESS,
    8/8 tests, and confirmed compiled class files are major version 65 (Java 21) via `javap`.
    No Java 18–21 language features adopted in existing code (toolchain-only change).
  - Added `@org.springframework.modulith.ApplicationModule` to the 8 modules that lacked it
    (tenant, schema, status, ledger, holder, consumer, rbac, connector) — all 11 modules now
    carry the annotation consistently. `ModulithBoundariesTest` stays green.
  - Extracted `CredentialService#toView` into a new `CredentialMapper` class
    (`credential/domain/CredentialMapper.java`, module-private, injects `SchemaCatalog` to
    resolve `schemaCode`) per CONVENTIONS.md §5's manual-mapper-class rule. No behavior
    change — same 8/8 tests green.
  - `docs/STATE.md`: split "Decisions made" into per-session subsections, deleted the stale
    "`ddl-auto: update` kept" line (false since KH-0.2.1), moved 3 durable conventions to
    `docs/CONVENTIONS.md` (entity visibility, Checkstyle logger/MethodName exceptions, new
    spring-modulith-upgrade DDL-diff rule).
  - `docs/CONVENTIONS.md`: added the 3 moved conventions (§2, §5, §6) plus a new PR rule
    (§9) requiring same-PR concurrency/correctness tests for core invariant logic changes,
    effective 2026-07-13.
  - `specs/FS-0.2-database-baseline.md` status header updated to note §5.7 (error-codes.md)
    is partially deferred to KH-0.6 — approved 2026-07-13.
  - Inspected `claim_code.disclosures_enc`: confirmed unencrypted (in fact entirely unset,
    not just plaintext) — logged as an open blocker below rather than implemented, per
    instructions.
- 2026-07-13: KH-0.2.1 — Flyway V1__baseline enterprise schema
  - `src/main/resources/db/migration/V1__baseline.sql`: all 13 business tables from
    `specs/FS-0.2-database-baseline.md` (tenant, issuer_key, credential_schema, holder,
    status_list, credential, claim_code, consuming_party(+_schema), consumption_event,
    app_user, role, user_role, audit_log) + Spring Modulith's official `event_publication`
    schema (copied verbatim from `spring-modulith-events-jdbc:1.2.4`). Seeds the default
    tenant + 3 default roles (PLATFORM_ADMIN, TENANT_ADMIN, ISSUER_OPERATOR).
  - `ddl-auto: validate` is now live; Flyway (`flyway-core` + `flyway-database-postgresql`
    + `spring-modulith-starter-jdbc`) is the only schema source.
  - New shared infra: `LocalizedText`/`LocalizedTextConverter` (the one JPA converter for
    every `name_i18n`/`label_i18n` jsonb column), `TenantContext` (fixed default-tenant UUID
    until KH-2.1), `Uuidv7` (D1: app-generated, time-ordered PKs — replaces
    `UUID.randomUUID()` everywhere).
  - Minimal persistence + one find-or-create cross-module method added to 4 previously-stub
    modules: `schema` (`SchemaCatalog#ensurePublished/#findById`), `holder`
    (`HolderDirectory#ensureHolder`), `status` (`StatusListAllocator#allocate`,
    pessimistic-lock based — no `RETURNING`-without-`@Modifying` trick, see decisions below),
    `consumer` (`ConsumingPartyRegistry#ensure`). `tenant` gets a `Tenant` entity + repo only
    (no API — see `TenantContext` above). `rbac` stays a pure stub (tables + seed rows only,
    no Java).
  - `credential` module rewritten to match the new schema end to end: `Credential`,
    `ConsumptionEvent` entities now carry FKs (`schema_id`, `holder_id`, `status_list_id`,
    `consuming_party_id`) instead of denormalized strings; new `ClaimCode` entity/table;
    `CredentialService#issue`/`#consume` orchestrate schema/holder/status/consumer APIs;
    `#issueClaimCode` added. `DemoSeeder` now issues a full document (schema + holder +
    credential + claim_code).
  - 8 tests, all green: `MigrationCleanBootTest`, `DemoSeederIntegrationTest`,
    `ConcurrentConsumeTest` (50 threads, exactly 1 success), `ConsumptionEventIdempotencyTest`
    (duplicate idempotency_key → unique violation), `AuditLogAppendOnlyTest` (UPDATE/DELETE
    rejected by trigger), `TenantNameI18nCheckTest` (missing `ar` → CHECK violation),
    `ModulithBoundariesTest`. All use a Testcontainers Postgres via a singleton-container base
    class (`support/IntegrationTestSupport`) so the suite boots the app once.
  - README.md added to all 11 modules (Work rule 1).
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

## Decisions made

### Session KH-0.1.1 (2026-07-13)
- **Cross-module key access**: `credential` module depends on `key :: api` (the `KeySigner`
  interface in `key/api/`). `SoftKeyService` in `key/domain/` is module-private.
  Named-interface mechanism: `@NamedInterface("api")` on `key/api/package-info.java`.
- **`credential/api/` named interface**: `@NamedInterface("api")` exposes only DTO records.
  `Credential` entity, `CredentialService`, repositories are module-private (in sub-packages).
- **DemoSeeder placement**: inside `credential/seed/` module, `@Profile({"local","dev"})`.
  Depends directly on `CredentialService` (same module — no API interface needed).

### Session KH-0.2.1 (2026-07-13)
- **UUIDv7 everywhere**: added `shared.Uuidv7` per spec D1; every entity's `id` is now
  app-generated UUIDv7, not `UUID.randomUUID()` (v4).
- **Status-list allocation is pessimistic-lock, not `UPDATE...RETURNING`**: first attempt used
  a native `@Query` with `UPDATE ... RETURNING` and no `@Modifying`, which is a
  known-fragile Spring Data JPA pattern. Switched to `@Lock(PESSIMISTIC_WRITE)` on the finder
  + plain read-increment-save in `StatusListAllocatorService` — same atomicity guarantee,
  standard Spring Data JPA.
- **Cross-module credential dependencies expanded**: `credential/package-info.java`
  `allowedDependencies` grew from `{key :: api}` to `{key :: api, schema :: api, holder ::
  api, status :: api, consumer :: api, shared}` — issuing/consuming now must resolve every FK
  the baseline schema requires. `shared` is listed by module name alone (no `::`) because
  `LocalizedText`/`TenantContext`/`Uuidv7` live in its open root package, not a named `api`
  sub-package.
- **`ConsumingPartyRegistry#ensure`, `SchemaCatalog#ensurePublished`, `HolderDirectory
  #ensureHolder` are find-or-create, not real onboarding**: real API-key issuance (KH-1.4.3),
  schema authoring (KH-1.x), and holder registration UX (KH-1.x) remain future work. These
  exist only so the baseline schema's `NOT NULL` FKs can be satisfied today.
- **Testcontainers + Docker Desktop 4.58 / Engine 29 compatibility**: Testcontainers
  1.20.1's default docker-java client negotiates an API version too old for Engine 29
  (`MinAPIVersion: 1.44`), which Docker Desktop rejects with an empty-bodied HTTP 400
  instead of a clear error. Fixed via `src/test/resources/docker-java.properties`
  (`api.version=1.44`) — see testcontainers-java issue #11235. If `mvn verify` fails locally
  with "Could not find a valid Docker environment" and `docker info` works fine from a shell,
  this is almost certainly the cause.
- **`docs/error-codes.md` / `ErrorCode` enum NOT created this session**: CLAUDE.md work rule 3
  (exception hierarchy, error envelope) is out of scope for KH-0.2.1 — it's a dedicated future
  task. `shared/README.md` notes this explicitly so it isn't mistaken for an oversight.

### Session Housekeeping (2026-07-14)
- **Java 21, toolchain-only**: bumped per an approved architecture-review decision made
  2026-07-13. **`CLAUDE.md`'s "Stack (frozen)" line still says "Java 17"** — this session was
  scoped to exactly the items requested and did not include editing `CLAUDE.md`; whoever
  reviews this should update that line too, or it will read as a stale contradiction.
- **`CredentialMapper` needs `SchemaCatalog`, not a pure entity→DTO function**: `Credential`
  only stores `schema_id`; `CredentialView` needs the human-readable `schemaCode`. The mapper
  is a `@Component` with constructor-injected `SchemaCatalog`, not a static utility — still
  matches CONVENTIONS.md §5's intent (a dedicated class, not inline mapping in the service).
- **`@ApplicationModule` added with no `allowedDependencies` on the 8 newly-annotated
  modules**: they have no cross-module dependencies today, so the bare annotation is
  sufficient; it only documents the module boundary, it doesn't change enforcement (Modulith
  already treated these as modules structurally, annotated or not).

### Session KH-0.2.2 (2026-07-14)
- **Lock file lives at repo-root `db/migration-checksums.lock`, not
  `src/main/resources/db/`**: it has no runtime purpose — only tests/CI read it — so it
  shouldn't be bundled into the deployable JAR the way `src/main/resources` contents are.
- **UNREGISTERED (new, unlisted migration) is a hard build failure, not a warning**: the task
  explicitly asked for the failure message to say "adding a new migration requires adding its
  checksum line" — this only self-serves future sessions if it's impossible to miss, i.e. the
  build actually fails until the line is added.
- **Chose a standalone bash script over a dedicated Maven plugin binding for the CI-prep
  layer**: `MigrationImmutabilityTest` already gives Maven-verify-time enforcement for free
  (it's a normal Surefire test); the script's job is specifically to be invocable *without* a
  JVM/Maven bootstrap, which is what makes it a cheap early CI step later.

### Session KH-0.3.1 (2026-07-14)
- **Single job, not split into separate "checksum" / "build" jobs**: splitting would add
  GitHub Actions job-startup overhead (each job gets its own fresh VM) for no real benefit —
  the checksum step already runs first within the one job and fails the whole run immediately
  if it fails, which is all "fail-fast ordering" required.
- **No `concurrency` group / run cancellation, no caching beyond `actions/setup-java`'s
  built-in `cache: maven`**: kept the workflow to exactly what the task asked for; nice-to-have
  CI ergonomics (auto-cancel superseded runs, etc.) can be added later without needing to
  revisit this decision.
- **Verified the Docker Desktop `api.version=1.44` pin against a real runner instead of
  reasoning about it**: Docker's API is backward-compatible so it was likely fine, but "likely
  fine" isn't the same as confirmed — the task asked to confirm, so the first PR's CI run is
  the actual evidence, not an assumption.

### Session KH-0.5 (2026-07-15)
- **`KeyProvider` scoped to pure crypto, not literally SEC §3's four-method
  sign/publicJwks/rotate/keys shape**: the spec diagram lists `KeyProvider` as "the complete
  SPI (sign / publicJwks / rotate / keys)," but giving the swappable interface DB/tenant/
  lifecycle awareness would mean a future `KmsProvider` has to know about `issuer_key` rows and
  the state machine — the opposite of D3's promise ("swap provider = config change, zero
  code"). Split instead: `KeyProvider` = generate/sign/publicKey against an opaque
  `providerRef`; `KeyLifecycleService` = everything DB/tenant/state-machine, calling into
  whichever `KeyProvider` is active. D1–D4 as literally stated are unaffected — this is an
  internal domain-layer split, invisible outside `key/domain/`.
- **`KeyLifecycleService` (and `PublishedKey`) are `public` Java classes despite being
  Modulith-module-private**: `key/web/JwksController` is a different Java package from
  `key/domain/`, so package-private (the default) would make it uncompilable. Same precedent
  CONVENTIONS.md §5 already documents for JPA entities — Java visibility can't express
  Modulith module-privacy; `ModulithBoundariesTest`'s package-based analysis is what actually
  enforces the boundary, not `public`/package-private.
- **No REST endpoint for `rotate()`, by design (matches the approved D-decisions, not a gap)**:
  spec FS-0.5 §5 is explicit that admin-triggered rotation is KH-2.2 (needs RBAC to gate it).
  `KeyLifecycleService.rotate()` is `@Transactional` and fully correct today; it's exercised
  only by tests until then.
- **`KeyBootstrap` runs in every profile, not just `local`/`dev`**: unlike `DemoSeeder`, a
  production boot with zero issuer keys is a broken deployment, not a missing convenience —
  there is no other provisioning path yet (explicitly temporary; see the module README).
- **Command-line-style `--key=value` args, not `.properties(...)`, for the two
  multi-`SpringApplicationBuilder`-run tests**: `SpringApplicationBuilder.properties(String...)`
  registers a *lowest-precedence* "defaultProperties" source — `application.yml`'s own
  `spring.datasource.url` entry (with its `${SPRING_DATASOURCE_URL:localhost:5432}` fallback)
  wins over it every time, so the override was silently ignored and the second/third context in
  each test tried to reach a real `localhost:5432` (refused). `.run("--key=value", ...)` args
  have near-top precedence and actually override the yml. `@DynamicPropertySource` (used by
  `IntegrationTestSupport`) doesn't have this problem — it operates at a different layer
  (`ContextCustomizer`) that always wins regardless.

### Session KH-0.4 (2026-07-16)
- **`status` claim follows the IETF Token Status List draft shape
  (`status.status_list.{idx,uri}`), not a flatter `status.{idx,list}`**: spec D3's Arabic
  gloss ("status_list: list URL + idx") reads naturally as naming the real IETF field
  (`status_list`) containing `uri`+`idx` — matching a real spec beats inventing a bespoke
  shape, and it costs nothing extra now. `uri` is a placeholder (the raw `status_list_id`) —
  no real bitstring endpoint exists before KH-1.3, so there is nothing to point it at yet.
- **`IssueRequest` gained `sdFields` rather than inventing a schema-authoring path**: real
  schema authoring (mandatory vs. optional claims_def fields, typed editor) is KH-1.x and out
  of scope. `DemoSeeder`/any dynamic-schema caller needed *some* way to express "these fields
  are optional" for D2 to be exercisable at all; a nullable `List<String>` request field
  (null → everything withholdable, preserving old single-caller behavior) was the smallest
  change that didn't touch the schema module's actual authoring model.
- **`SchemaRef` widened (`claimsDefJson`, `sdFields`) instead of adding a new schema-module
  method**: the verify path's mandatory-disclosure check (D2) needs the full claims_def field
  list, and `credential` already depends on `schema :: api` — widening the existing DTO some
  callers already hold needs no new cross-module dependency and no new boundary for
  `ModulithBoundariesTest` to police.
- **`issueClaimCode` now takes the `sdJwt` presentation as a parameter, not just the
  credential id**: disclosures are never persisted anywhere in plaintext (by design, P1-
  adjacent), so a later, independent call has no other way to reach them. The only holder of
  the plaintext disclosures at any point after `issue()` returns is whoever received the
  `IssueResponse` — so encryption has to happen from that same handoff, not from a separate
  DB-only lookup. `DemoSeeder` was updated to pass `issued.sdJwt()` through immediately.
- **`springdoc-openapi-starter-webmvc-api` (no UI), 2.8.17 not 3.0.3**: CLAUDE.md's frozen
  stack already names springdoc-openapi, so adding the dependency itself isn't a new decision
  — but *how much* of it to add is: the "-api" artifact gives annotations + `/v3/api-docs`
  JSON generation without exposing a live Swagger UI, which felt premature with zero auth in
  front of anything before KH-0.6. Version 2.8.17 (not the newer 3.0.x line) because 3.x
  targets Spring Boot 3.4+/Spring Framework 6.2+ and this project is pinned to Boot 3.3.4.
- **Only `/issue` and `/verify` got OpenAPI annotations, not every endpoint**: the task scope
  was "the changed issue/verify request-response shapes" specifically; retroactively
  annotating `/consume`, `/revoke`, `/{id}` (unchanged this session) would have been scope
  creep beyond what was asked, and full coverage + CI publishing is explicitly KH-1.6 per
  spec FS-0.4 §7.

> Durable conventions formerly logged here (entity visibility, the Checkstyle
> logger/MethodName exceptions) now live in `docs/CONVENTIONS.md` §2/§5 — this file only
> keeps session-scoped decisions. The stale "`ddl-auto: update` kept" note has been removed
> (superseded by KH-0.2.1: `ddl-auto: validate` is live — see Last completed above).

## Environment facts
- Local: Windows + IntelliJ + Docker Desktop. Shared network `khatm-net` created.
- DB exposed on :5432 for IntelliJ; API on :8080.
- Maven 3.9.9 (must export PATH manually: `export PATH="$MAVEN_HOME/bin:$PATH"`).
- Toolchain is Java 21 (`pom.xml` `java.version`/`maven.compiler.release`). Both JDK 17
  (`C:\Program Files\Java\jdk-17`, original) and JDK 21 (Eclipse Temurin,
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`) are installed on this machine;
  `JAVA_HOME` must point at the JDK 21 install for builds to target the right release —
  IntelliJ project SDK and JAVA_HOME both point at Eclipse Temurin 21 (fixed manually
  2026-07-15) — the JDK 17 install remains on disk but is unused.
- Default tenant strategy: single default tenant row until KH-2.1 — fixed UUID
  `00000000-0000-0000-0000-000000000001`, seeded by `V1__baseline.sql`, mirrored in Java as
  `sy.khatm.platform.shared.TenantContext.DEFAULT_TENANT_ID`.
- Docker Desktop on this machine needs `src/test/resources/docker-java.properties`
  (`api.version=1.44`) for Testcontainers to connect at all (see decisions above).
- Docker Desktop does not auto-start on login on this machine — `docker info` fails until it's
  launched manually (or via `"/c/Program Files/Docker/Docker/Docker Desktop.exe" &`, then
  polled until `docker info` succeeds, ~10–30s). Needed before any Testcontainers-backed
  `mvn verify` run.

## Open decisions / blockers
- **`claim_code.disclosures_enc` — encryption half CLOSED as of KH-0.4 (2026-07-16).**
  `CredentialService#issueClaimCode` now populates real disclosures (extracted from the SD-JWT
  presentation) and AES-256-GCM encrypts them via `ClaimsEncryptionService` before persisting
  (spec FS-0.4 D7; key from `khatm.claims.enc-key`, fails startup outside `local` if missing).
  **What remains open**: the expiry-zeroing worker that clears `disclosures_enc` back to
  `NULL` on claim or timeout, and the actual claim-delivery path to a wallet — both are
  KH-1.2.1, which still depends on the ADR-09 worker skeleton (not yet built). `decrypt()`
  exists on `ClaimsEncryptionService` now (tested), ready for that worker to call.

## Next up (ordered)
1. KH-0.6 Console auth + API-key filter + `rbac`/`shared.audit_log` write path (a fuller
   version than KH-0.5's minimal direct-insert audit rows) + the `KhatmException`/`ErrorCode`
   hierarchy (CLAUDE.md work rule 3 — still not started) + the message bundles
   (`messages_en.properties`/`messages_ar.properties` don't exist yet — CLAUDE.md work rule 2)
2. KH-0.3.3 — staging auto-deploy (explicitly out of scope for KH-0.3.1's CI pipeline)
3. KH-1.2.1 — claim-delivery worker + `disclosures_enc` expiry-zeroing (needs the ADR-09
   worker skeleton; the encryption half it depends on landed in KH-0.4)
4. KH-1.3 — Status List: publish the real signed bitstring artifact endpoint (the `status`
   claim's `uri` is a placeholder until then, KH-0.4 D3)
5. KH-1.6 — published OpenAPI contract: full endpoint annotation coverage (KH-0.4 only
   annotated `/issue`/`/verify`) + CI-published `openapi.json`
6. KH-2.2 — RBAC, needed before `KeyLifecycleService.rotate()` can get a REST endpoint
7. KH-2.3 — KMS-backed `KeyProvider` (D3 swap), KH-3.1 — HSM
