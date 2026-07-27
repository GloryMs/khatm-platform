# status

Credential status list management — a compact, gzip-compressed Status List 2021-style
revocation bitstring per tenant, published as a signed artifact (spec FS-1.3).

**Events in:** none. **Events out:** `StatusListChanged` (KH-1.3) — fired inside the
revoke transaction's bit-flip, routed to the existing `khatm.credential.events` stream.

**Tables owned:** `status_list` (V1 baseline) + `signed_artifact`/`artifact_version` columns (V3,
KH-1.3).

**Cross-module API (`api/`):**
- `StatusListAllocator#allocate` — atomically reserves a `(status_list_id, status_idx)` pair at
  issue time (KH-0.2.1); a `SELECT ... FOR UPDATE` row lock serialises concurrent allocations.
- `StatusListRevoker#revoke` — flips a bit and bumps the list's version inside the caller's revoke
  transaction, then publishes `StatusListChanged` (KH-1.3 D3).
- `StatusListLookup#findRef` — read-only resolution of a list's version + public URL, used by
  `/verify` and the claim-redeem path to fill the additive `statusList*` response fields (KH-1.3
  D6/D7). The URL itself is built by `StatusListUriBuilder` (module-private), which delegates the
  base-URL half to `shared.PublicUrlBuilder` (`khatm.public-base-url` — chore/public-base-url) so
  this module owns only the `/sl/{tenantSlug}/{listCode}` path shape, never the host.
- `StatusListLookup#findArtifact` (KH-2.1, spec FS-2.1 D8) — resolve a tenant's list by
  `(tenantId, listCode)` and return its signed artifact, publishing it first if stale/never
  published (the lazy-publish fallback, moved here from the old `status.web.StatusListController`).
  Backs `tenant.web.TenantStatusListController`'s `GET /sl/{tenantSlug}/{listCode}` — that endpoint
  relocated out of this module (KH-2.1 D8) specifically so `status` never needs a reverse dependency
  on `tenant :: api` to resolve the path's slug (would be a Modulith cycle, since `tenant` already
  depends on this module's `StatusListAllocator#ensureList` for onboarding).
- `StatusListAllocator#ensureList` (KH-2.1, spec FS-2.1 D6) — create-if-absent, no bit consumed;
  the tenant-onboarding admin plane's "give this new tenant a default status list" step. Takes an
  explicit `tenantId` (unlike `allocate`, which resolves `TenantContext.current()`) since the
  onboarding admin caller's own tenant is never the tenant being onboarded.

**Worker (`worker/`):** `StatusListChangedHandler` (near-real-time publish on event) and
`StatusListPublishSweepWorker` (periodic catch-up safety net) together sign and store the compact
JWS artifact, debounced to one republish per version (KH-1.3 D5). Worker-role only.

**Web (`web/`):** none as of KH-2.1 — `GET /sl/{tenantSlug}/{listCode}` moved to
`tenant.web.TenantStatusListController` (see above).

**Status:** KH-0.2.1 added persistence + allocation; KH-1.3 completes signed artifact publication,
revoke-time bit flipping, and the additive `statusList*` verify fields; KH-2.1 widens the `api`
surface for tenant onboarding + relocates the public HTTP endpoint to `tenant.web`. Capacity
rollover remains future work.
