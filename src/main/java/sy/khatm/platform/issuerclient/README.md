# issuerclient

Machine-to-machine **issuer clients** (KH-2.8.1, spec FS-2.7a): an external system — a ministry's
back office, a university registrar — that is allowed to issue credentials for one tenant without a
human console session.

**Responsibilities.** The `issuer_client` entity and its `khi_<prefix>_<secret>` key; the lifecycle
(create, rotate with a grace window, suspend, resume, revoke); the per-client schema allowlist
(`issuer_client_schema`, deny-by-default); the tenant-level *holder HMAC secret* (D9); the
parent-organisation plane for managing a direct child's clients.

**Events in:** none. **Events out:** none. (State changes are audit rows, not events.)

**Tables owned:** `issuer_client`, `issuer_client_schema` (V18). `credential.issuer_client_id` is a
column of `credential`'s table — this module never touches it.

## The key (D2)

`khi_` + 10 lowercase base32 chars (the lookup **prefix**) + `_` + 32 random bytes as base64url
(the **secret**, 43 chars). Shown **once**, in the response that creates/rotates the client.

* Stored: the prefix **without** the `khi_` tag (so a dump scan for `khi_` can never match a stored
  value) and an **argon2id** hash of the secret — nothing else. The raw key exists in one response
  body and nowhere else.
* Sent as `Authorization: Bearer khi_...` — the same header the consuming-party keys (`khk_`) use.
* Verified by prefix lookup → argon2id compare → only then status/expiry/rotation/tenant checks (a
  wrong secret never reveals a client's state). An unknown prefix burns one dummy argon2 evaluation
  so timing does not distinguish it. `last_used_at` is written at most once a minute.
* Every failure is the **same external body** (`401 KH-AUTH-0401`); the real reason
  (`unknown-prefix`, `bad-secret`, `suspended`, `revoked`, `retired`, `expired`, `tenant-suspended`,
  `malformed`) is only in the `ISSUER_CLIENT_AUTH_FAILED` audit row, throttled to one per prefix per
  minute. No automatic lockout — knowing a prefix must never be enough to silence an issuer (D12).

## What a client may do (D4)

Exactly: `POST /api/v1/credentials/issue`, `POST /api/v1/credentials/bulk`, and
`GET /api/v1/credentials/{id}` **for credentials it issued itself** (anyone else's id is a 404, never a
403). Every other route is `403 KH-AUTH-0403`, decided by **one** central rule registered first in
`rbac.security.SecurityConfig` (`ScopeGuard#requireIssuerClientAllowedRoute`) — not by per-path
conditions — and pinned by a test that walks every registered route. The `tenantId` of every request
comes from the `issuer_client` row, never from the request.

Issuance additionally requires the credential's schema to be on the client's allowlist, and stamps
`credential.issuer_client_id`; the audit trail records `actor_type = API_KEY`, `actor_id = client id`.

## Rotation (D10)

`POST /api/v1/issuer-clients/{id}/rotate` mints a replacement (own key, same schemas/expiry,
`rotated_from` = old) and moves the old client to `RETIRING` with `retire_after = now + hours`
(default 24, max 72, `0` allowed). Both keys work inside the window. Past `retire_after` the old key
is refused immediately; `RetiringSweeper` (worker role only) then flips the stored status to
`REVOKED` and audits it. Only an `ACTIVE` client can be rotated.

## Holder HMAC secret (D9)

`holderRef` sent by a connector is `HMAC-SHA256(secret, nationalId)` as 64 hex chars — the platform
never sees the national ID and never computes the HMAC. The secret is generated **once per root
tenant** (top of the KH-2.6 hierarchy) when its first client is created, written to Vault **KV v2**
at `khatm/data/tenants/<root-slug>/holder-hmac` with check-and-set (`cas: 0`), shown to the
operator once, and inherited by children (a child's clients never generate one). Reached with a
plain-HTTP `RestClient` like `key.domain.VaultTransitProvider` — there is no Spring Vault
dependency — using the same `khatm.keys.vault.*` address/token. Only when
`khatm.keys.vault.enabled=true`; a Vault failure rolls the whole creation back
(`KH-ICL-0503`) so a secret is never generated without being shown. *Recovery:* the secret lives in
Vault; if a creation response is lost after Vault committed, an operator reads it there.

## Endpoints

| Route | Gate |
|---|---|
| `GET/POST /api/v1/issuer-clients`, `POST /{id}/rotate\|suspend\|resume\|revoke` | `key:manage`, console session |
| `GET/POST /api/v1/org/children/{id}/issuer-clients`, `POST .../{clientId}/rotate\|suspend\|resume\|revoke` | `org:admin` (existing `/api/v1/org/**` rule), direct child only, else `KH-ORG-0404` |

The org plane is keyed by the child tenant's **id**, like every other route under
`/api/v1/org/children/{id}` (the spec text says `{slug}`; the live plane won).

## Configuration

`khatm.issuer.rotation.default-retire-hours` (24), `.max-retire-hours` (72);
`khatm.issuer.holder-secret.kv-path` (`khatm/tenants`); `khatm.worker.issuer-client.retire-sweep-ms`
(60000). Issuance contract: `khatm.issuance.forbidden-claim-names` (in `credential`).

## Demo seeding

`seed.DemoIssuerClientSeeder` runs under the **`local` profile only** and prints a throwaway demo key
(and holder secret, when Vault is on) at INFO — once, on first boot against an empty database. This
is the single, deliberate exception to "no key in any log line": it is a disposable local-machine
secret and the bean does not exist under any other profile (a test pins that). Everywhere else no
log line — at any level — contains a key, a secret, or a `holderRef`.

## Module rules

`issuerclient` depends on `shared`, `tenant :: api`, `schema :: api` — **not** on `rbac` (`rbac`
depends on `issuerclient :: api`; the reverse would be a cycle). `credential` depends on
`issuerclient :: api` only (`IssuerClientSchemaAccess`) and never on its domain.
