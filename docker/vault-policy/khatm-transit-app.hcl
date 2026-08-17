# khatm-transit-app.hcl — least-privilege Vault ACL policy for khatm-api/khatm-worker's app token
# (spec FS-2.3 D5, KH-2.3b). See docs/deploy-staging.md "Vault hardening" for how to mint a
# token/AppRole bound to this policy.
#
# The app only ever needs to: create a transit key per issuer_key row (VaultTransitProvider
# #generate), sign (VaultTransitProvider#sign), and — for completeness with the KeyProvider SPI,
# not on any hot path today (KeyLifecycleService#resolvePublicKey reads issuer_key.public_jwk
# instead) — read a key's public material. It NEVER needs to export key material (keys are
# created exportable=false), delete a key, manage other secrets engines, or touch anything
# outside transit/. This policy is intentionally that narrow.
#
# ---------------------------------------------------------------------------------------------
# CORRECTION 2026-08-15 — "update" added to transit/keys/*, established empirically on staging.
#
# This policy previously granted only ["create", "read"] on transit/keys/*, on the reasoning that
# VaultTransitProvider#generate always POSTs a brand-new key name (kid is tenantSlug:key-<seq>,
# strictly incrementing, never re-POSTed) and that Vault would therefore ask for "create".
# That reasoning is WRONG in practice: the first live SOFT→VAULT migration on bunny staging
# failed with 403 from Vault → IntegrityException KH-KEY-0503, on a name that did not yet exist.
# Adding "update" fixed it immediately, with no other change.
#
# Most likely cause (consistent with the observation, not verified against Vault's source in that
# session): Vault's ACL layer only distinguishes create-vs-update on paths whose backend
# registers an ExistenceCheck. transit/keys/:name does not appear to register one, so every
# write to it is evaluated as "update" regardless of whether the key already exists.
# "create" is retained anyway — harmless, and correct if Vault ever adds the existence check.
#
# Scope note on the widening: this glob also covers transit/keys/<name>/config, so a token
# bound to this policy could flip a key's config (e.g. deletion_allowed, exportable). It still
# CANNOT read key material: transit/export/* is not granted here, and export is the only path
# that returns private key bytes. Deletion additionally requires "delete", also not granted.
# ---------------------------------------------------------------------------------------------

path "transit/keys/*" {
  capabilities = ["create", "update", "read"]
}

path "transit/sign/*" {
  capabilities = ["update"]
}

path "transit/verify/*" {
  capabilities = ["update"]
}

# Explicitly nothing else — no "delete" on transit/keys/*, no transit/export/*, no sys/*, no
# other secrets engine path. A token bound to this policy cannot export key material, delete a
# key, disable the transit engine, or read any other tenant's non-transit secrets even if some
# existed in the same Vault cluster.
