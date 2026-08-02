# khatm-transit-app.hcl — least-privilege Vault ACL policy for khatm-api/khatm-worker's app token
# (spec FS-2.3 D5, KH-2.3b). See docs/deploy-staging.md "Vault hardening for production" for how
# to mint a token/AppRole bound to this policy.
#
# The app only ever needs to: create a transit key per issuer_key row (VaultTransitProvider
# #generate), sign (VaultTransitProvider#sign), and — for completeness with the KeyProvider SPI,
# not on any hot path today (KeyLifecycleService#resolvePublicKey reads issuer_key.public_jwk
# instead) — read a key's public material. It NEVER needs to export key material (keys are
# created exportable=false), delete/rotate-in-Vault's-own-sense a key, manage other secrets
# engines, or touch anything outside transit/. This policy is intentionally that narrow.

path "transit/keys/*" {
  capabilities = ["create", "read"]
}

path "transit/sign/*" {
  capabilities = ["update"]
}

path "transit/verify/*" {
  capabilities = ["update"]
}

# Explicitly nothing else — no "transit/keys/*" delete, no sys/*, no other secrets engine path.
# A token bound to this policy cannot export key material, disable the transit engine, or read
# any other tenant's non-transit secrets even if some existed in the same Vault cluster.
