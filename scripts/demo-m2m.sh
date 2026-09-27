#!/usr/bin/env bash
# demo-m2m.sh — live walkthrough of machine-to-machine issuance (spec FS-2.7a; KH-2.8.1 + KH-2.8.2).
#
# curl + openssl only. Run against the LOCAL compose stack (`docker compose up`, profile `local`),
# which seeds one demo issuer client and prints its key ONCE at first boot.
#
#   BASE            platform base URL                       (default http://localhost:8080)
#   KHI_KEY         the demo key, `khi_...`                  (default: scraped from `docker compose logs khatm-api`)
#   HOLDER_SECRET   the tenant holder HMAC secret            (default: scraped from the same log line; needs Vault on)
#   NATIONAL_ID     a made-up national id to HMAC            (default 02010012345)
#   SCHEMA_CODE     schema the demo client may issue against (default CriminalRecordExtract/v1)
#   IDEM_KEY        the Idempotency-Key of the replay story  (default demo-001; a key already used on this
#                   database makes step 1 a replay too — pick a new one, e.g. IDEM_KEY=demo-002)
#
# The replay story (KH-2.8.2) pauses for the wallet between steps when run in a terminal.
# Nothing here is destructive; it issues a few local demo credentials.

set -u
BASE="${BASE:-http://localhost:8080}"
NATIONAL_ID="${NATIONAL_ID:-02010012345}"
SCHEMA_CODE="${SCHEMA_CODE:-CriminalRecordExtract/v1}"
IDEM_KEY="${IDEM_KEY:-demo-001}"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
show() { printf '%s\n' "$1" | (command -v jq >/dev/null 2>&1 && jq . 2>/dev/null || cat); }
brief() { printf '%s\n' "$1" | (command -v jq >/dev/null 2>&1 && jq -c '{code,messageKey}' 2>/dev/null || cat); }
field() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
pause() { if [ -t 0 ]; then read -r -p "   ... press Enter to continue " _; fi; }

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
HDRS="$(mktemp)"; trap 'rm -f "$HDRS"' EXIT

# POST with the given Idempotency-Key ("" = no header); prints status + replay header, echoes the body.
issue() {
  local path="$1" key="$2" body="$3" idem=()
  [ -n "$key" ] && idem=(-H "Idempotency-Key: $key")
  local out; out="$(curl -s -D "$HDRS" -X POST "$BASE$path" "${auth[@]}" "${idem[@]}" -d "$body")"
  local status replayed
  status="$(sed -n '1s/^HTTP[^ ]* \([0-9]*\).*/\1/p' "$HDRS")"
  replayed="$(grep -i '^Idempotent-Replayed:' "$HDRS" | tr -d '\r' | sed 's/^[^:]*: *//')"
  printf '   HTTP %s%s\n' "$status" "${replayed:+   Idempotent-Replayed: $replayed}" >&2
  printf '%s' "$out"
}
qr() { [ -n "$1" ] && echo "   QR payload:  {\"v\":1,\"api\":\"$BASE\",\"code\":\"$1\"}"; }

# holderRef = HMAC-SHA256(secret, nationalId), 64 lowercase hex. The platform never sees the id.
HOLDER_REF="$(printf '%s' "$NATIONAL_ID" | openssl dgst -sha256 -hmac "$HOLDER_SECRET" | sed 's/^.* //')"
BODY="{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$HOLDER_REF\",\"maxUses\":1,\"mintClaimCode\":true,\"claims\":{\"result\":\"NO_RECORD\",\"caseNumber\":\"CR-M2M-1\"}}"

say "1. Issue with mintClaimCode:true and Idempotency-Key: $IDEM_KEY  ->  200, claimCode, issuerClientId set"
FIRST="$(issue /api/v1/credentials/issue "$IDEM_KEY" "$BODY")"
show "$FIRST"
CRED_ID="$(field "$FIRST" id)"
CODE_A="$(field "$FIRST" claimCode)"
qr "$CODE_A"

say "2. The exact same request again  ->  200 + Idempotent-Replayed: true, a DIFFERENT claimCode (claimCodeReissued:true)"
SECOND="$(issue /api/v1/credentials/issue "$IDEM_KEY" "$BODY")"
show "$SECOND"
CODE_B="$(field "$SECOND" claimCode)"
qr "$CODE_B"
echo "   Wallet: scan the FIRST code (A) -> rejected (it died with the reissue); then scan B -> the document shows."
echo "   A = $CODE_A"
echo "   B = $CODE_B"
pause

say "3. Same request a third time, after the wallet claimed  ->  200 replay, claimed:true, no claimCode"
show "$(issue /api/v1/credentials/issue "$IDEM_KEY" "$BODY")"

say "4a. Same key, different body  ->  422 KH-IDEM-0422"
brief "$(issue /api/v1/credentials/issue "$IDEM_KEY" "${BODY/CR-M2M-1/CR-M2M-CHANGED}")"

say "4b. No Idempotency-Key at all  ->  400 KH-IDEM-0400 (mandatory for issuer clients)"
brief "$(issue /api/v1/credentials/issue "" "$BODY")"

say "5. Direct sdJwt mode (no mintClaimCode), replayed  ->  sdJwt:null, deliveryLost:true"
DIRECT="{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$HOLDER_REF\",\"claims\":{\"result\":\"NO_RECORD\",\"caseNumber\":\"CR-M2M-2\"}}"
DIRECT_KEY="$IDEM_KEY-direct"
issue /api/v1/credentials/issue "$DIRECT_KEY" "$DIRECT" >/dev/null
show "$(issue /api/v1/credentials/issue "$DIRECT_KEY" "$DIRECT")"

say "6. Read own credential  ->  200"
curl -s -o /dev/null -w '   HTTP %{http_code}\n' "${auth[@]}" "$BASE/api/v1/credentials/$CRED_ID"

say "7. Same key on /revoke  ->  403 KH-AUTH-0403"
brief "$(curl -s -X POST "$BASE/api/v1/credentials/$CRED_ID/revoke" "${auth[@]}")"

say "8. Tampered key  ->  401 KH-AUTH-0401 (same body for unknown / wrong / suspended / revoked / expired)"
BAD="${KHI_KEY%?}A"; [ "$BAD" = "$KHI_KEY" ] && BAD="${KHI_KEY%?}B"
brief "$(curl -s -X POST "$BASE/api/v1/credentials/issue" -H "Authorization: Bearer $BAD" -H "Content-Type: application/json" -d '{}')"

say "9. A claim named nationalId  ->  400 KH-ISS-0400"
brief "$(issue /api/v1/credentials/issue "$IDEM_KEY-nid" "{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$HOLDER_REF\",\"claims\":{\"nationalId\":\"$NATIONAL_ID\"}}")"

say "10. holderRef that is not 64 hex  ->  400 KH-ISS-0400"
brief "$(issue /api/v1/credentials/issue "$IDEM_KEY-badref" "{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"$NATIONAL_ID\",\"claims\":{\"a\":\"b\"}}")"

say "11. Manual checks"
echo "   Human session without holderRef (V1-b): log in to the console, copy the KHATM_SESSION and XSRF-TOKEN cookies, then"
echo "     curl -s -X POST $BASE/api/v1/credentials/issue -H 'Content-Type: application/json' \\"
echo "       -H 'Cookie: KHATM_SESSION=<session>; XSRF-TOKEN=<xsrf>' -H 'X-XSRF-TOKEN: <xsrf>' \\"
echo "       -d '{\"schemaCode\":\"$SCHEMA_CODE\",\"holderRef\":\"\",\"claims\":{\"result\":\"NO_RECORD\"}}'"
echo "     -> 200 and a generated 64-hex holderRef in the response."
echo "   Vault:  docker compose exec -e VAULT_TOKEN=\${KHATM_KEYS_VAULT_TOKEN:-khatm-vault-dev-root-token-change-me} khatm-vault \\"
echo "             vault kv get -mount=khatm tenants/khatm-default/holder-hmac"
echo "   Logs:   docker compose logs khatm-api | grep -E \"$IDEM_KEY|khi_\""
echo "           -> only the one-time LOCAL demo key line from first boot; never the Idempotency-Key, a claim"
echo "              code, a holderRef, or the secret."
