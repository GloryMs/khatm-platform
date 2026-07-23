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

**Worker (`worker/`):** `StatusListChangedHandler` (near-real-time publish on event) and
`StatusListPublishSweepWorker` (periodic catch-up safety net) together sign and store the compact
JWS artifact, debounced to one republish per version (KH-1.3 D5). Worker-role only.

**Web (`web/`):** `GET /sl/{tenantSlug}/{listCode}` — public, `application/jose`, `ETag`/`Cache-Control`
served (KH-1.3 D2).

**Status:** KH-0.2.1 added persistence + allocation; KH-1.3 completes the module — signed artifact
publication, revoke-time bit flipping, the public endpoint, and the additive `statusList*` verify
fields. Capacity rollover remains future work.
