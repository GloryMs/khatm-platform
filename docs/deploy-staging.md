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
