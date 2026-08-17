# Staging deployment (KH-0.3.3)

> Status: **inert / config-gated.** The publish half is live (every push to `main` builds and
> pushes the image to GHCR); the deploy half does nothing until a staging host is wired up.
> Activation is a one-time config task — no code change, no new PR. This document is the runbook.
>
> Swagger UI (`/swagger-ui.html`, `/v3/api-docs`) is intentionally **OFF** outside the `local`/`dev`
> profiles — including on this staging host — until KH-1.6 decides contract publication.

## What runs today

`.github/workflows/release.yml` (triggers on every push to `main`):

1. **`build-and-push`** — builds the image from the repo `Dockerfile` and pushes it to the GitHub
   Container Registry as `ghcr.io/<owner>/khatm-platform`, tagged `latest` **and** the short commit
   SHA. `main` is branch-protected, so anything that reaches it already passed CI (`ci.yml`) on its
   PR — this workflow ships, it does not re-test.
2. **`deploy-staging`** — SSHes to the host and runs `docker compose pull && docker compose up -d`.
   It is guarded by `if: ${{ secrets.STAGING_SSH_HOST != '' }}`. With that secret unset the job is
   **skipped** (gray ⏭ in the run graph), never failed. Its sibling `deploy-staging-skipped` prints
   a notice explaining why, so the log is unambiguous.

## One-time host preparation (do this once, on the staging host)

1. **Docker.** Install Docker Engine + Compose v2 on a Linux host.
2. **Network.** `docker network create khatm-net` (the compose file declares it `external`).
3. **GHCR auth.** `docker login ghcr.io -u <github-user> --password-stdin` with a Personal Access
   Token that has `read:packages`, so `docker compose pull` can fetch the image. This stores
   credentials in `~/.docker/config.json` on the host — no token is handled by the workflow. (If you
   instead flip the GHCR package to public, this step is unnecessary.)
4. **Deploy directory.** Create one (e.g. `~/khatm-platform`) holding:
   - a compose file that references the **image** (not `build: .`) — see the snippet below;
   - a real `.env` (copied from `.env.example`, then filled in — see "Secrets & configuration" in
     the README). SOPS encryption for this file is wired in the `khatm-deploy` repo, out of scope
     here.
5. **SSH key.** Generate a dedicated key pair; install the **public** key on the host
   (`~/.ssh/authorized_keys` for the deploy user). The workflow authenticates with the **private**
   key stored as a repo secret.
6. **Repo secrets.** In GitHub → *Settings → Secrets and variables → Actions*, add the secrets in
   the table below.

### The prod compose file (host-side)

The repo's `docker-compose.yml` builds from source (`build: .`). On the host you want to **pull**
the published image instead, so use a standalone compose file there — for example
`docker-compose.prod.yml` (kept in `khatm-deploy`, shown here for reference):

```yaml
name: khatm-platform
services:
  khatm-postgres:
    image: postgres:16
    environment: { POSTGRES_DB: khatm, POSTGRES_USER: khatm, POSTGRES_PASSWORD: "${POSTGRES_PASSWORD}" }
    volumes: [khatm_pgdata:/var/lib/postgresql/data]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U khatm -d khatm"], interval: 5s, timeout: 3s, retries: 10 }
    networks: [khatm-net]
  khatm-redis:
    image: redis:7
    healthcheck: { test: ["CMD", "redis-cli", "ping"], interval: 5s, timeout: 3s, retries: 10 }
    networks: [khatm-net]
  khatm-api:
    image: ghcr.io/gloryms/khatm-platform:latest   # pull, don't build
    env_file: [.env]
    ports: ["8080:8080"]
    volumes: [khatm_keys:/var/khatm/keys]
    depends_on: { khatm-postgres: { condition: service_healthy }, khatm-redis: { condition: service_healthy } }
    networks: [khatm-net]
  khatm-worker:
    image: ghcr.io/gloryms/khatm-platform:latest
    env_file: [.env]
    volumes: [khatm_keys:/var/khatm/keys]
    depends_on: { khatm-postgres: { condition: service_healthy }, khatm-redis: { condition: service_healthy } }
    networks: [khatm-net]
volumes: { khatm_pgdata: , khatm_keys: }
networks: { khatm-net: { external: true } }
```

`khatm-worker` sets `SPRING_PROFILES_ACTIVE=local,worker` (or the prod worker profile) in its `.env`
section — the two roles are selected by Spring profile, one image (ADR-09).

## Database role for multi-tenancy RLS (KH-2.1, spec FS-2.1 D3)

`khatm-api`/`khatm-worker` connect as a locked-down `khatm_app` role — no `BYPASSRLS`, not a table
owner, granted only `SELECT`/`INSERT`/`UPDATE` (+ `DELETE` on the one documented exception) by
`V7__rls_policies.sql`'s own `GRANT` statements. Flyway itself still runs as the owner role
(`khatm`), split onto its own `SPRING_FLYWAY_*` datasource. Both `.env` and the compose file need
the following, on top of the prod compose snippet above:

```yaml
  khatm-postgres:
    # ...as above, plus:
    environment:
      # ...POSTGRES_DB/POSTGRES_USER/POSTGRES_PASSWORD as above, plus:
      KHATM_APP_DB_PASSWORD: "${KHATM_APP_DB_PASSWORD}"
    volumes:
      - khatm_pgdata:/var/lib/postgresql/data
      - ./docker/postgres-init:/docker-entrypoint-initdb.d:ro   # copy this dir to the host too
  khatm-api:
    # ...as above, plus:
    environment:
      SPRING_DATASOURCE_USERNAME: khatm_app
      SPRING_DATASOURCE_PASSWORD: "${KHATM_APP_DB_PASSWORD}"
      SPRING_FLYWAY_USER: khatm
      SPRING_FLYWAY_PASSWORD: "${POSTGRES_PASSWORD}"
  khatm-worker:
    # same four SPRING_DATASOURCE_*/SPRING_FLYWAY_* lines as khatm-api
```

`.env` needs a `KHATM_APP_DB_PASSWORD` alongside the existing `POSTGRES_PASSWORD`.

**Fresh host (empty `khatm_pgdata` volume):** the mounted `docker/postgres-init/` script runs
automatically on Postgres's first boot (the official image runs everything under
`/docker-entrypoint-initdb.d/` exactly once, only against a freshly-initialized data directory) —
no manual step needed.

**Existing staging host deployed before KH-2.1:** an already-initialized `khatm_pgdata` volume will
**not** pick up the mounted init script — `docker-entrypoint-initdb.d` only runs against a fresh
data directory. Before deploying the first KH-2.1+ image, run this once against the existing
database (Flyway's `V7__rls_policies.sql` migration `GRANT`s to `khatm_app` and will fail outright
if the role doesn't exist yet):

```sh
docker compose exec khatm-postgres psql -U khatm -d khatm -c "
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'khatm_app') THEN
      CREATE ROLE khatm_app LOGIN PASSWORD '<same value as KHATM_APP_DB_PASSWORD>'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
  END
  \$\$;
  GRANT CONNECT ON DATABASE khatm TO khatm_app;
  GRANT USAGE ON SCHEMA public TO khatm_app;
"
```

Then add the `.env`/compose changes above and redeploy as usual — Flyway's `V7` migration handles
every other `GRANT` itself on that same run.

## Vault hardening (staging and production)

Vault runs on bunny Magic Containers staging as of 2026-08-15. The checklist below is what
was actually executed there; production repeats it with the noted differences.

### Image — custom, not `hashicorp/vault` directly

MC enforces the `no-new-privileges` kernel flag. The official image's `docker-entrypoint.sh`
drops privileges to the `vault` user via `su-exec`/`sudo`, which that flag blocks, so the
container exits before Vault prints a single log line (silent, no error from Vault itself —
the only clue is `sudo: The "no new privileges" flag is set` in container stderr).

The workaround is a thin image that runs Vault directly as the `vault` user with no privilege
transition at all — `khatm-deploy/vault/Dockerfile`, published as
`ghcr.io/gloryms/khatm-vault:1.17-mc`. Security posture is unchanged (same non-root `vault`
user); the privilege drop is baked into the image instead of performed at runtime. Startup
Command and Container Arguments in MC must both be left at **Default** — the image's own
ENTRYPOINT/CMD carry the config path.

### Storage — shared volume, subdirectory

MC caps an application at 2 persistent volumes, both already in use, so Vault shares the
existing `data` volume with Postgres: volume `data` mounted at `/data` in the Vault container,
with `"storage": {"file": {"path": "/data/vault"}}`. Verify the Postgres container's `PGDATA`
is itself a subdirectory (not the volume root) so the two never overlap.

Consequence to keep in mind: losing the `data` volume now loses the database **and** the Vault
key material together.

Deviations from the local hardened compose, both staging-accepted and recorded in STATE:
`disable_mlock: true` (MC grants no `IPC_LOCK`), and `tls_disable: true` on the listener
(all access is either loopback inside the pod or terminated at bunny's edge).

### Networking

All six containers (`khatm-api`, `khatm-worker`, `khatm-postgres`, `redis`, `pgadmin`,
`khatm-vault`) run in a single MC pod and share a network namespace, so the app reaches Vault
at `http://127.0.0.1:8200`. No service DNS, no public endpoint required for app traffic.

A CDN endpoint on Vault's port 8200 is needed **only** for operator init/unseal from outside
(bunny provides no shell; a direct network path is not a shell). Disable caching on its Pull
Zone, restrict it by IP where possible, and remove or re-restrict it once initialization is
done.

### Initialize, unseal, transit

Run from the operator's machine against the Vault CDN endpoint. Git Bash is required for the
JSON payloads — PowerShell's quoting mangles them.

```bash
V=https://<vault-ep>.b-cdn.net

# One-time init. The response carries 5 unseal keys + root token and is NEVER shown again.
# Have a password manager open BEFORE running this.
curl -s -X POST $V/v1/sys/init -H "Content-Type: application/json" \
  -d '{"secret_shares":5,"secret_threshold":3}'

# Unseal: 3 shares, one call each, until sealed:false
curl -s -X PUT $V/v1/sys/unseal -d '{"key":"<key-n>"}'

# Transit engine (204 No Content on success — empty output is correct)
curl -s -X POST $V/v1/sys/mounts/transit -H "X-Vault-Token: <root>" -d '{"type":"transit"}'
```

Then apply the policy **from the committed file**, never hand-typed:

```bash
python -c "import json; print(json.dumps({'policy': open('docker/vault-policy/khatm-transit-app.hcl').read()}))" > policy.json
curl -s -X PUT $V/v1/sys/policies/acl/khatm-transit -H "X-Vault-Token: <root>" --data-binary @policy.json
rm policy.json

# Verify what actually landed
curl -s -H "X-Vault-Token: <root>" $V/v1/sys/policies/acl/khatm-transit
```

Mint the app token from that policy — never a root token in the application:

```bash
curl -s -X POST $V/v1/auth/token/create -H "X-Vault-Token: <root>" \
  -d '{"policies":["khatm-transit"],"ttl":"0","renewable":true,"display_name":"khatm-api-staging"}'
```

`ttl: 0` (non-expiring) is a **staging** choice, to avoid issuance stopping unattended when a
token lapses. Production uses AppRole with a bounded period instead.

### ⚠ Policy correction — `transit/keys/*` requires `update`

The first live migration attempt on staging failed with `KH-KEY-0503` even though Vault was
unsealed, the token was valid, and the transit engine was mounted. Vault was returning 403 on
`POST /v1/transit/keys/<new-name>` — a name that did not yet exist — because the policy granted
only `create` and `read`.

`transit/keys/*` must carry **`["create", "update", "read"]`**. Vault's ACL layer only
distinguishes create from update on paths that register an existence check, and
`transit/keys/:name` appears not to; every write there is evaluated as `update`.

`docker/vault-policy/khatm-transit-app.hcl` has been corrected accordingly. Any Vault instance
provisioned before 2026-08-15 from the old file needs the policy re-applied.

Diagnostic worth reusing: when `KH-KEY-0503` appears with Vault demonstrably up, test the exact
call the app makes, with the app token, before suspecting the network:

```bash
curl -s -X POST -H "X-Vault-Token: <app-token>" \
  $V/v1/transit/keys/<prefix>-<tenant>:key-<n+1> -d '{"type":"ecdsa-p256"}'
```

A `permission denied` here is a policy problem; a connection error is a networking problem.
`VaultTransitProvider` maps both to the same `KH-KEY-0503`, so they are indistinguishable from
the application error alone.

### Env vars on both `khatm-api` and `khatm-worker`

```
KHATM_KEYS_VAULT_ENABLED=true
KHATM_KEYS_VAULT_ADDRESS=http://127.0.0.1:8200
KHATM_KEYS_VAULT_TOKEN=<app token from the policy above>
```

Set them on **both** containers. With `enabled=true` and a blank token,
`VaultTransitProvider` fails startup by design.

### Migration to the Vault provider

There is no migration endpoint — migration is a rotation with an explicit provider
(spec FS-2.3 D6), per `docs/runbooks/key-rotation.md` step 1b:

```
POST /api/v1/admin/signing-keys/rotate
{ "provider": "VAULT" }
```

Once per tenant. Afterwards, rotations with no body inherit the active key's provider, so
routine rotation stays on Vault without any further explicit request.

**The console cannot send this.** `rotateSigningKey()` posts no body and the UI has no provider
selector, even though the vendored contract exposes `RotateKeyRequest.provider`. Until that gap
is closed, the migration call is made directly — e.g. from DevTools on an authenticated console
session, reusing the session cookie and `X-XSRF-TOKEN`.

### Operational reality: every pod restart re-seals Vault

Any container update in MC restarts the whole pod, which re-seals Vault. While sealed:

- issuance fails `503 KH-KEY-0503` (fail-closed, by design — never a silent SOFT fallback);
- verification, JWKS, and the status list keep working, because
  `KeyLifecycleService#resolvePublicKey` reads `issuer_key.public_jwk` from Postgres and never
  round-trips through the provider.

Recovery is `unseal-staging-vault.sh` (operator-run, keys read with echo off). **Check
`sys/seal-status` before diagnosing any `KH-KEY-0503`** — a recent redeploy is by far the most
common cause, and this was confirmed repeatedly during the staging rollout.

Do not change any MC container setting between unsealing and performing a rotation; the change
will re-seal Vault mid-procedure.

## Staging secrets (GitHub repo → Settings → Secrets → Actions)

| Secret                | Required to activate | Purpose                                                       |
|-----------------------|:--------------------:|--------------------------------------------------------------|
| `STAGING_SSH_HOST`    | **yes — this is the gate** | Hostname/IP of the staging host.                       |
| `STAGING_SSH_USER`    | yes                  | SSH user the workflow logs in as.                             |
| `STAGING_SSH_KEY`     | yes                  | SSH **private** key (PEM) matching the host's authorized key. |
| `STAGING_SSH_PORT`    | no (default `22`)    | SSH port, if non-standard.                                    |
| `STAGING_DEPLOY_DIR`  | no (default `khatm-platform`) | Absolute path on the host holding the compose file + `.env`. |

## Activation

Set **`STAGING_SSH_HOST`** (and the rest of the table). On the next push to `main`:

- `build-and-push` publishes `ghcr.io/gloryms/khatm-platform:latest` (+ SHA tag);
- `deploy-staging` SSHes in, `docker compose pull`s the new image, and `docker compose up -d`
  restarts the stack.

No code change. To roll back, point the host's compose file at a prior SHA tag and re-run
`docker compose up -d`.

## Security notes

- `STAGING_SSH_KEY` is the crown jewel of this setup. Scope the deploy host user to the least
  privilege that can run `docker compose`, and consider a dedicated (non-root) account in the
  `docker` group. Rotate the key if the host or the secret is ever suspect.
- GitHub secrets are encrypted at rest and masked in logs. The deploy workflow never sees GHCR
  pull credentials (those live on the host), and never logs the SSH key.
- Keep the GHCR package **private** unless you have a reason otherwise; pulling then requires the
  host's `read:packages` PAT from step 3.
