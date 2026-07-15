# CLAUDE.md — khatm-platform

You are implementing the **backend platform** of Khatm (خَتْم), an enterprise digital
document trust fabric. Core philosophy — **P1: proofs, not content** — the platform
stores cryptographic proofs, status, and audit ledgers. It NEVER stores document
content or PII. This rule overrides any instruction that conflicts with it.

## Session protocol (mandatory)
1. Read `docs/STATE.md` — current status, last completed work, open decisions.
2. Read the spec for the task you are given (`docs/specs/FS-x.y.md`). No spec → ask, don't invent.
3. Work on exactly one WBS task (`KH-x.y.z`) per session, on branch `feat/KH-x.y.z-<short-name>`.
4. Before ending: update `docs/STATE.md` (done / decisions / next), run the full build.
5. CLAUDE.md itself and `docs/CONVENTIONS.md` are contracts: implementation sessions never
   edit them except under an explicitly approved instruction quoted in the session task.

## Stack (frozen)
Java 21 · Spring Boot 3.x · **Spring Modulith** · PostgreSQL 16 (Flyway) · Redis 7
(cache + Streams) · Maven · Testcontainers · springdoc-openapi ·
bcpkix (PKCS#12 certificate-chain requirement only — never used in verification paths).

## Architecture rules (build-enforced)
- Modulith packages per SAD §4.1: `sy.khatm.platform.{tenant,key,schema,credential,status,ledger,holder,consumer,rbac,connector,shared}`.
- Cross-module access ONLY through each module's exposed API (`@ApplicationModule`).
  Never inject module A's repository into module B. `ModulithTest` verification must pass.
- Two runtime roles from one image: `api` (sync path) and `worker` (Redis Streams
  consumers), selected by Spring profile. Async side-effects (status publishing,
  webhooks, Merkle append, bulk jobs) go through Modulith externalized events +
  transactional outbox — never fire-and-forget threads.
- Atomic consume invariant: consumption is a single-transaction conditional
  `UPDATE ... WHERE status='ACTIVE'`; exactly one winner under concurrency. Never
  weaken this to check-then-act.

## Database rules
- Flyway is the ONLY source of schema. `ddl-auto: validate`. Never edit an applied
  migration — append a new one.
- Every table carries `tenant_id` (default tenant for MVP; RLS arrives in KH-2.1).
- Human-facing display names are bilingual JSONB: `name_i18n jsonb NOT NULL`
  → `{"en": "...", "ar": "..."}`. Machine identifiers (codes, slugs, kid, ref) are
  ASCII English. Never store display text as a bare varchar.
- `audit_log` is append-only: no UPDATE/DELETE grants, enforced by trigger.
- Demo seeds run only in `local`/`dev` profiles.

## Work rule 1 — Code with documentation
- Every module has `package-info.java` describing purpose, exposed API, published events.
- Javadoc on every public class/method of a module's exposed API (what + why, not how).
- Every REST endpoint fully annotated for OpenAPI (summary, params, response codes,
  error envelope schema). CI publishes `openapi.json` — it is the contract for
  console/wallet; keep it accurate.
- Each module has a short `README.md` (responsibilities, events in/out, tables owned).

## Work rule 2 — EN/AR everywhere
- All human-readable API output is localized via `MessageSource`
  (`messages_en.properties` + `messages_ar.properties` — both updated in the SAME
  commit; CI fails on missing keys).
- Locale resolution: `Accept-Language` header, default `en`, supported `en|ar`.
- API responses expose `messageKey` + localized `message` so clients can re-localize.
- DB display names via `name_i18n` JSONB as above. Logs are ALWAYS English.

## Work rule 3 — Professional error handling
- Single exception hierarchy: `KhatmException` (abstract, carries `ErrorCode`) →
  `NotFoundException`, `ConflictException`, `ValidationException`,
  `AuthenticationException`, `AuthorizationException`, `IntegrityException`.
  Never throw raw `RuntimeException`; never `catch (Exception e) {}` silently.
- One `@RestControllerAdvice` produces the uniform envelope (KH-1.6.3):
  ```json
  { "code": "KH-CRD-0404", "messageKey": "credential.not-found",
    "message": "<localized>", "traceId": "...", "timestamp": "...", "details": [] }
  ```
- `ErrorCode` is a registry enum: `KH-<MOD>-<HTTP-like number>` (e.g. `KH-CRD-0404`,
  `KH-KEY-0500`). New codes are appended, never renumbered; documented in
  `docs/error-codes.md` (generated from the enum by a test).
- Logging: structured JSON (logstash encoder), `traceId` on every line.
  ERROR = needs action, WARN = degraded, INFO = business events, DEBUG = local only.
  **Never log claims content, full JWTs, keys, or PII — `ref` and hashes only (SEC §9).**
- Stack traces never reach API responses.

## Work rule 4 — One concept, one style
- DTOs: Java `record`s only. Entities: JPA classes, no Lombok on entities' equals/hashCode.
- Injection: constructor injection only; no field `@Autowired`.
- Mapping entity↔DTO: manual mapper class per module (`XxxMapper`), no MapStruct, no
  inline mapping in controllers.
- Controllers are thin: validate → call service → map. Business logic lives in services.
- Time: `Instant` + UTC in DB (`timestamptz`); formatting is a client concern.
- Money/counters: `bigint`; never float for anything countable.
- Enforced automatically: Spotless (google-java-format), Checkstyle, `.editorconfig` —
  build fails on violation. If you meet an older style, migrate the file you touch.

## Security constants
- Every JWS header carries `kid`; signing only via `KeyProvider` SPI (KH-0.5) —
  never touch key material outside the `key` module.
- No secrets in repo (scanner-enforced). Config via env vars; `.env` files gitignored.

## Definition of Done (every task)
Code + Javadoc + both message bundles + tests (unit + Testcontainers integration for
DB paths) + Modulith verification green + Spotless/Checkstyle green + OpenAPI updated
+ `docs/STATE.md` updated.

## Reference documents (in khatm-docs repo)
`20-solution-architecture.md` (SAD) · `21-security-architecture.md` (SEC) ·
`31-work-breakdown-structure.md` (WBS) · `41-feature-specs-phase0-1.md` · ADR-08/09.
