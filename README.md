# khatm-platform

[![CI](https://github.com/GloryMs/khatm-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/GloryMs/khatm-platform/actions/workflows/ci.yml)

Backend platform for Khatm (خَتْم), an enterprise digital document trust fabric. Built as a
Spring Modulith on Java 21 / Spring Boot / PostgreSQL, it stores cryptographic proofs, status,
and audit ledgers for issued credentials — never document content or PII. See `CLAUDE.md` for
the full architecture rules and `docs/STATE.md` for current project status.

## Running locally

```bash
docker network create khatm-net          # once
docker compose up -d --build             # api + worker + postgres + redis
```

The `local` profile supplies every required secret with a documented default, so the stack boots
with zero setup: a bootstrap console admin (`admin` / the password logged at startup) and a demo
credential are seeded automatically. API on http://localhost:8080.

**Testing from a real device (e.g. a wallet on your phone):** the platform embeds its own public
base URL into every self-referential URL it hands a client (today: the status-list URI, spec
FS-1.3). The `local` default is `http://localhost:8080`, which only resolves on the machine
running Docker — a phone on the same LAN cannot reach it. Before `docker compose up`, export your
machine's LAN IP instead:

```bash
export KHATM_PUBLIC_BASE_URL=http://<your-LAN-IP>:8080   # e.g. http://192.168.1.42:8080
docker compose up -d --build
```

### Restore-from-zero smoke test (Phase-0 exit criterion)

```bash
./scripts/smoke.sh
```

One command — works on Windows Git-Bash and in CI. Boots the stack from clean, asserts the JWKS
endpoint and a full login → issue → verify cycle, tears it down to zero (`docker compose down -v`),
boots again on the same image, and re-asserts. This is the single command behind the `compose-smoke`
CI job.

## Secrets & configuration

`.env.example` is the complete contract of every runtime environment variable (which are required
outside `local`, which have local defaults). Copy it to `.env` (gitignored) for local overrides, or
use it as the input the `khatm-deploy` repo encrypts with SOPS for real environments (SOPS wiring
happens in `khatm-deploy`, not here). Keystores (`*.p12`), `.env`, and local secret paths are
gitignored.

## CI

`.github/workflows/ci.yml` runs on every PR into `main` and every push to `main`:

- **Build and verify** — migration checksum guard + `mvn verify` (Spotless, Checkstyle, Modulith
  boundaries, all tests).
- **Trivy vuln scan (KH-0.3.2)** — `trivy fs` (dependencies) + `trivy image` (runtime image),
  failing on CRITICAL/HIGH that have a fix available (`--ignore-unfixed`). Allowlist: `.trivyignore`.
- **gitleaks (KH-0.3.4)** — secret scanning of the PR's commits; triaged exceptions in
  `.gitleaks.toml`.
- **compose-smoke** — the restore-from-zero proof above.

## API contract

`docs/api/openapi.json` is the published, committed OpenAPI contract — generated from the running
application's own `/v3/api-docs` by `OpenApiContractTest` (never hand-edited, same self-serve
philosophy as `docs/error-codes.md`), and kept fresh by that same test running inside `mvn verify`
on every PR and every push to `main`: the build fails if the committed file drifts from what the
code actually serves. On merge to `main` it is therefore always the authoritative, fetchable
contract — raw URL:
`https://raw.githubusercontent.com/GloryMs/khatm-platform/main/docs/api/openapi.json`.

**Additive-only from KH-1.6-early on.** Every business and auth endpoint now lives under
`/api/v1/**` — the one breaking path change this platform ever makes with a straight face, done
while there were zero external clients. From this point, a path rename or removal needs its own
ADR; new endpoints/fields are always safe to add.

**Consuming it:** console and wallet generate their HTTP client types from this file (typegen) —
never hand-written request/response types (ADR-08). Point your generator at the raw URL above, or
at `http://localhost:8080/v3/api-docs` for a local stack (Swagger UI at
`http://localhost:8080/swagger-ui/index.html`, local/dev profiles only).

## Release & deploy (KH-0.3.3)

`.github/workflows/release.yml` runs on push to `main`: it builds and pushes the image to the GitHub
Container Registry (`ghcr.io/gloryms/khatm-platform`, tagged `latest` + short SHA). The staging
deploy job is **inert** until a host is configured — gated on a secret, it skips cleanly when that
secret is absent. Activation (one-time host prep + the exact secret names) is documented in
`docs/deploy-staging.md`.
