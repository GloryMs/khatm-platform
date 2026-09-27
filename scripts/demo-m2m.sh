#!/usr/bin/env bash
# demo-m2m.sh — KH-2.8.1 live walkthrough of machine-to-machine issuance (spec FS-2.7a).
#
# curl + openssl only. Run against the LOCAL compose stack (`docker compose up`, profile `local`),
# which seeds one demo issuer client and prints its key ONCE at first boot.
#
#   BASE            platform base URL                       (default http://localhost:8080)
#   KHI_KEY         the demo key, `khi_...`                  (default: scraped from `docker compose logs khatm-api`)
#   HOLDER_SECRET   the tenant holder HMAC secret            (default: scraped from the same log line; needs Vault on)
#   NATIONAL_ID     a made-up national id to HMAC            (default 02010012345)
#   SCHEMA_CODE     schema the demo client may issue against (default CriminalRecordExtract/v1)
#
# Nothing here is destructive; it issues a few local demo credentials.

set -u
BASE="${BASE:-http://localhost:8080}"
NATIONAL_ID="${NATIONAL_ID:-02010012345}"
SCHEMA_CODE="${SCHEMA_CODE:-CriminalRecordExtract/v1}"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
show() { printf '%s\n' "$1" | (command -v jq >/dev/null 2>&1 && jq . 2>/dev/null || cat); }

scrape() { docker compose logs khatm-api 2>/dev/null | grep -m1 "$1" | sed -E "s/.*$1 *= *//" | tr -d '\r'; }
KHI_KEY="${KHI_KEY:-$(scrape 'apiKey')}"
HOLDER_SECRET="${HOLDER_SECRET:-$(scrape 'holderHmac')}"

if [ -z "$KHI_KEY" ]; then
  echo "No demo key. It is printed once at first boot; if this database already existed, rotate the client"
  echo "from the console/API, or wipe the volume:  docker compose down -v && docker compose up" >&2
  exit 1
fi
if [ -z "$HOLDER_SECRET" ]; then
  echo "No holder secret in the log (Vault may be off, or this is not the first boot)."
  echo "Read it from Vault instead:"
  echo "  docker compose exec -e VAULT_TOKEN=\${KHATM_KEYS_VAULT_TOKEN:-khatm-vault-dev-root-token-change-me} khatm-vault \\"
  echo "    vault kv get -mount=khatm -field=secret tenants/khatm-default/holder-hmac" >&2
  exit 1
fi

auth=(-H "Authorization: Bearer $KHI_KEY" -H "Content-Type: application/json")

# holderRef = HMAC-SHA256(secret, nationalId), 64 lowercase hex. The platform never sees the id.
HOLDER_REF="$(printf '%s' "$NATIONAL_ID" | openssl dgst -sha256 -hmac "$HOLDER_SECRET" | sed 's/^.* //')"

say "1. Issue with the demo key  ->  expect 200, issuerClientId set, claimed:false"
ISSUED="$(curl -s -X POST "$BASE/api/v1/credentials/issue" "${auth[@]}" \
  -d "{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$HOLDER_REF\",\"maxUses\":1,\"claims\":{\"result\":\"NO_RECORD\",\"caseNumber\":\"CR-M2M-1\"}}")"
show "$ISSUED"
CRED_ID="$(printf '%s' "$ISSUED" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"

say "2. Get a claim code for the wallet (bulk of one with mintClaimCodes)  ->  scan it with the wallet"
echo "   NOTE: /issue itself returns the sdJwt, not a claim code; the M2M-reachable way to obtain the code"
echo "   is bulk with mintClaimCodes:true (claim-code minting is not on the issuer-client allowlist)."
BULK="$(curl -s -X POST "$BASE/api/v1/credentials/bulk" "${auth[@]}" \
  -d "{\"schemaCode\":\"$SCHEMA_CODE\",\"mintClaimCodes\":true,\"items\":[{\"pseudoRef\":\"$HOLDER_REF\",\"claims\":{\"result\":\"NO_RECORD\",\"caseNumber\":\"CR-M2M-2\"}}]}")"
show "$BULK"
CLAIM="$(printf '%s' "$BULK" | sed -n 's/.*"claimCode":"\([^"]*\)".*/\1/p')"
[ -n "$CLAIM" ] && echo "   QR payload:  {\"v\":1,\"api\":\"$BASE\",\"code\":\"$CLAIM\"}"

say "3. Read own credential  ->  200"
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "${auth[@]}" "$BASE/api/v1/credentials/$CRED_ID"

say "4. Same key on /revoke  ->  403 KH-AUTH-0403"
curl -s -X POST "$BASE/api/v1/credentials/$CRED_ID/revoke" "${auth[@]}" | (command -v jq >/dev/null && jq -c '{code,messageKey}' || cat)

say "5. Tampered key  ->  401 KH-AUTH-0401 (same body for unknown / wrong / suspended / revoked / expired)"
BAD="${KHI_KEY%?}A"; [ "$BAD" = "$KHI_KEY" ] && BAD="${KHI_KEY%?}B"
curl -s -X POST "$BASE/api/v1/credentials/issue" -H "Authorization: Bearer $BAD" -H "Content-Type: application/json" -d '{}' \
  | (command -v jq >/dev/null && jq -c '{code,messageKey}' || cat)

say "6. A claim named nationalId  ->  400 KH-ISS-0400"
curl -s -X POST "$BASE/api/v1/credentials/issue" "${auth[@]}" \
  -d "{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$HOLDER_REF\",\"claims\":{\"nationalId\":\"$NATIONAL_ID\"}}" \
  | (command -v jq >/dev/null && jq -c '{code,messageKey}' || cat)

say "7. holderRef that is not 64 hex  ->  400 KH-ISS-0400"
curl -s -X POST "$BASE/api/v1/credentials/issue" "${auth[@]}" \
  -d "{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$NATIONAL_ID\",\"claims\":{\"a\":\"b\"}}" \
  | (command -v jq >/dev/null && jq -c '{code,messageKey}' || cat)

say "8. Vault + logs (manual checks)"
echo "   Vault:  docker compose exec -e VAULT_TOKEN=\${KHATM_KEYS_VAULT_TOKEN:-khatm-vault-dev-root-token-change-me} khatm-vault \\"
echo "             vault kv get -mount=khatm tenants/khatm-default/holder-hmac"
echo "   Logs:   docker compose logs khatm-api | grep khi_   # only the one-time LOCAL demo line at first boot;"
echo "           after that: nothing. No holderRef and no secret may appear in any line."
