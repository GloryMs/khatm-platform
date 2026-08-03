#!/usr/bin/env bash
#
# scripts/smoke.sh — KH-0.3 Phase-0 exit criterion 3: restore-from-zero proof.
#
# THIS SCRIPT IS THE "ONE COMMAND" of doc 30 Phase-0 exit criterion 3. It runs identically on
# Windows Git-Bash and in CI (Linux): it boots the full stack from a clean state, asserts the
# two public endpoints and one authenticated end-to-end flow, tears the whole thing down to zero
# (named volumes included), boots it again, and re-asserts — proving the restore is repeatable,
# not a lucky first boot.
#
# Proves (per the session brief):
#   1. GET /.well-known/jwks.json -> 200 with >=1 key        (boot, Flyway V1+V2, KeyBootstrap)
#   2. login(local bootstrap admin) -> enroll+confirm TOTP -> issue a credential -> verify it ->
#      valid:true (AdminBootstrap, session auth, mandatory 2FA, SD-JWT signing + verify — true
#      end-to-end)
#   3. docker compose down -v, then up again -> checks 1 & 2 pass again (repeatable restore)
#
# Requires: docker (Compose v2) + curl + python3 (stdlib only, RFC 6238 TOTP — see totp_code()).
# Deliberately does NOT depend on jq — JSON is parsed with sed/grep so the same command works on
# a minimal Git-Bash and a CI runner.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_USER="${KHATM_BOOTSTRAP_ADMIN_USERNAME:-admin}"
ADMIN_PASS="${KHATM_BOOTSTRAP_ADMIN_PASSWORD:-khatm-local-dev-admin-change-me}"
SCHEMA_CODE="${SCHEMA_CODE:-CriminalRecordExtract/v1}"

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

bold() { printf '\n\033[1m>> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mSMOKE FAILED:\033[0m %s\n' "$*" >&2; exit 1; }

# docker-compose.yml declares khatm-net as `external`; create it if missing (idempotent).
ensure_network() {
  docker network inspect khatm-net >/dev/null 2>&1 || docker network create khatm-net
}

wait_for_api() {
  bold "Waiting for API at $BASE_URL (cold boot: Flyway V1+V2 + KeyBootstrap + AdminBootstrap)..."
  # The web server accepts connections BEFORE the ApplicationRunners finish, so /.well-known/jwks.json
  # returns 200 with {"keys":[]} for a moment until KeyBootstrap creates the ACTIVE key. Poll for a
  # NON-EMPTY key set, not merely a 200 — otherwise we race the bootstrap and see no keys.
  local i body
  for i in $(seq 1 150); do
    body="$(curl -s "$BASE_URL/.well-known/jwks.json" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q '"kty"'; then
      printf '  API ready (JWKS has a key) after ~%ss\n' "$i"
      return 0
    fi
    sleep 1
  done
  docker compose -f "$COMPOSE_FILE" logs --tail=40 khatm-api 2>/dev/null || true
  fail "API did not publish a JWKS key within 150s"
}

check_jwks() {
  bold "Check 1: GET /.well-known/jwks.json -> 200 with >=1 key"
  local body
  body="$(curl -fsS "$BASE_URL/.well-known/jwks.json")"
  printf '%s' "$body" | grep -q '"kty"' || fail "JWKS response carries no key: $body"
  printf '  JWKS OK\n'
}

# $1 = json text, $2 = key -> first string value (jq-free, tolerant of spacing)
json_value() {
  printf '%s' "$1" \
    | sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    | head -n1
}

# RFC 6238 TOTP from a base32 secret, standard-library Python only (no pyotp) — the same
# independent-implementation technique KH-2.2c's own live DoD used to verify the mandatory-2FA
# flow end to end (docs/STATE.md), reused here so this script can clear the wall itself.
totp_code() {
  python3 - "$1" <<'PY'
import base64, hashlib, hmac, struct, sys, time
secret = sys.argv[1]
key = base64.b32decode(secret)
counter = int(time.time()) // 30
msg = struct.pack(">Q", counter)
h = hmac.new(key, msg, hashlib.sha1).digest()
offset = h[-1] & 0x0F
code = (struct.unpack(">I", h[offset:offset + 4])[0] & 0x7fffffff) % 1000000
print(f"{code:06d}")
PY
}

check_e2e() {
  bold "Check 2: login -> /api/v1/auth/me -> issue -> verify (authenticated end-to-end)"

  # 2a. Login is CSRF-exempt + permitAll; the response sets the KHATM_SESSION cookie.
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
    "$BASE_URL/api/v1/auth/login")"
  [ "$code" = "200" ] || fail "login returned HTTP $code (expected 200)"

  # 2b. GET /api/v1/auth/me: proves the session, AND forces the XSRF-TOKEN cookie to be written
  #     (Spring Security 6 resolves the CSRF token lazily; a pure JSON API never triggers it,
  #     so the KH-0.6b console pattern of read-cookie/send-header needs this nudge).
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    "$BASE_URL/api/v1/auth/me")"
  [ "$code" = "200" ] || fail "/api/v1/auth/me returned HTTP $code (session not established)"

  local xsrf
  xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR")"
  [ -n "$xsrf" ] || fail "no XSRF-TOKEN cookie after /api/v1/auth/me — CSRF cookie was not written"

  # 2c. Enroll + confirm mandatory TOTP (KH-2.2c, spec FS-2.2 V1). The bootstrap admin holds
  # platform:admin/tenant:admin/key:manage/revoke — every one a mandatory-2FA scope — so a fresh
  # AdminBootstrap-created admin (exactly this script's own "restore-from-zero" scenario) is
  # walled to only enroll/confirm/me/logout/login/totp-challenge (403 KH-USR-1403) until this
  # runs. Not optional here: every later authenticated call in this function would 403 otherwise.
  local enroll_body secret totp
  enroll_body="$(curl -fsS \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H "X-XSRF-TOKEN: $xsrf" \
    -X POST "$BASE_URL/api/v1/users/me/totp/enroll")"
  secret="$(json_value "$enroll_body" secretBase32)"
  [ -n "$secret" ] || fail "totp/enroll response had no secretBase32: $enroll_body"
  totp="$(totp_code "$secret")"
  curl -fsS -o /dev/null \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $xsrf" \
    -d "{\"code\":\"$totp\"}" \
    "$BASE_URL/api/v1/users/me/totp/confirm" \
    || fail "totp/confirm failed with independently-computed code $totp for secret $secret"
  printf '  TOTP enrolled + confirmed\n'

  # 2d. Issue a credential (session auth + `issue` scope; admin holds it). Echo the CSRF token.
  local issue_body
  issue_body="$(curl -fsS \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $xsrf" \
    -d '{"schemaCode":"'"$SCHEMA_CODE"'","holderRef":"smoke-'"$$"'","maxUses":1,"validMinutes":60,"claims":{"result":"NO_RECORD","caseNumber":"SMOKE-001","issuedAt":"2026-07-17"},"sdFields":["caseNumber","issuedAt"]}' \
    "$BASE_URL/api/v1/credentials/issue")"

  local sdjwt
  sdjwt="$(json_value "$issue_body" sdJwt)"
  [ -n "$sdjwt" ] || fail "issue response had no sdJwt: $issue_body"
  printf '  issued credential, verifying...\n'

  # 2e. Verify it (public endpoint — no auth). Expect valid:true.
  local verify_body
  verify_body="$(curl -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"sdJwt\":\"$sdjwt\"}" \
    "$BASE_URL/api/v1/credentials/verify")"
  printf '%s' "$verify_body" | grep -Eq '"valid"[[:space:]]*:[[:space:]]*true' \
    || fail "verify did not return valid:true: $verify_body"
  printf '  verify OK (valid:true)\n'
}

# ----------------------------------------------------------------------------

# khatm-api and khatm-worker share one keystore volume + DB. Before this session, KeyBootstrap ran
# as a plain, unconditional ApplicationRunner in both roles, so starting them concurrently was a
# genuine race (see docs/STATE.md "Session KH-0.3-closure" for the reproduction and the sequenced
# boot_stack workaround this replaces). key.domain.KeyBootstrap is now gated behind
# khatm.web.enabled=true (this session's fix), so only the api role ever bootstraps the key —
# a single plain `docker compose up -d` starting both roles concurrently is the end-to-end proof
# the race is gone.
boot_stack() {
  bold "docker compose up -d --build (api + worker concurrently, unsequenced)"
  docker compose -f "$COMPOSE_FILE" up -d --build
}

bold "=== Phase 1: clean boot ==="
ensure_network
boot_stack
wait_for_api
check_jwks
check_e2e

bold "=== Phase 2: restore-from-zero (down -v, then up on the SAME image) ==="
docker compose -f "$COMPOSE_FILE" down -v >/dev/null
ensure_network   # external network survives `down`; re-check is harmless
boot_stack
wait_for_api
check_jwks
check_e2e

bold "ALL SMOKE CHECKS PASSED — boot + end-to-end + repeatable restore."
printf '\nThe stack has been left running. Tear it down with: docker compose -f %s down -v\n' \
  "$COMPOSE_FILE"
