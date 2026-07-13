# STATE — khatm-platform
> Updated at the end of EVERY Claude Code session. This file is the session anchor.

## Current phase / task
- Phase 0 — Production Foundation
- Active task: KH-0.1.1 (restructure POC api/ into Modulith packages) — NOT STARTED

## Last completed
- 2026-07-xx: Repository founded (CLAUDE.md, docs, compose, gitignore). POC code imported as-is.

## Environment facts
- Local: Windows + IntelliJ + Docker Desktop. Shared network `khatm-net` created.
- DB exposed on :5432 for IntelliJ; API on :8080.
- Default tenant strategy: single default tenant row until KH-2.1.

## Open decisions / blockers
- (none)

## Next up (ordered)
1. KH-0.1.1 Modulith packages + KH-0.1.2 boundary verification test
2. KH-0.2.1 Flyway V1__baseline (enterprise schema incl. tenant_id + name_i18n)
3. KH-0.2.2 ddl-auto=validate + migration CI check
4. KH-0.5 KeyProvider SPI (SoftKeyProvider, kid in JWS)
5. KH-0.4 SD-JWT signing upgrade
6. KH-0.6 Console auth + API-key filter + audit_log
