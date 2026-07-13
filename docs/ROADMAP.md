# ROADMAP — khatm-platform (backend-owned WBS tasks)
> Status: ☐ todo · ◐ in progress · ✔ done · Source of truth for scope: khatm-docs/31-WBS.

## Phase 0 (M1–M2)
| Task | Description | Spec | Status |
|---|---|---|---|
| KH-0.1.1 | Modulith packages per SAD §4.1 | SAD | ☐ |
| KH-0.1.2 | Boundary verification test (fails build) | SAD | ☐ |
| KH-0.1.4 | Polyrepo foundation (this repo) — per ADR-08 | ADR-08 | ✔ |
| KH-0.2.1 | Flyway V1__baseline — enterprise schema, tenant_id everywhere, name_i18n JSONB | FS-0.2* | ☐ |
| KH-0.2.2 | ddl-auto=validate + migration CI check | — | ☐ |
| KH-0.2.3 | Demo seeds only in local/dev | — | ☐ |
| KH-0.3.1 | CI: build + tests (Testcontainers) + image | — | ☐ |
| KH-0.3.2 | Dependency & container vulnerability scan gate | — | ☐ |
| KH-0.3.4 | Secrets mgmt; zero secrets in repo (scanner) | SEC | ☐ |
| KH-0.4.x | SD-JWT: _sd digests, disclosures, sd_fields, verify path | FS-0.4 | ☐ |
| KH-0.5.x | KeyProvider SPI + SoftKeyProvider + kid resolution | FS-0.5 | ☐ |
| KH-0.6.x | Console auth (session) + API-key filter + audit_log | — | ☐ |
| ADR-09 | Worker role: outbox + Redis Streams skeleton | ADR-09 | ☐ |

## Phase 1 (M3–M6) — backend share
| Task | Description | Spec | Status |
|---|---|---|---|
| KH-1.1.x (BE) | Schema mgmt API, issuance from claims_def, bulk CSV pipeline (worker), credential search + revoke | FS-1.1 | ☐ |
| KH-1.3.x | Signed Status List: entity, bit alloc, revoke→republish ≤60s, /verify checks list | FS-1.3 | ☐ |
| KH-1.4.x | Consumption hardening: persistent idempotency + concurrency CI test + ConsumingParty | FS-1.4 | ☐ |
| KH-1.6.x | /api/v1 + published OpenAPI + error envelope & reason-code registry | — | ☐ |

*FS-0.2 = short DB-baseline spec to be approved in the Claude.ai Project before KH-0.2.1 starts.
