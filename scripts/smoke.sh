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
#   2. login(local bootstrap admin) -> issue a credential -> verify it -> valid:true
#      (AdminBootstrap, session auth, SD-JWT signing + verify — true end-to-end)
#   3. docker compose down -v, then up again -> checks 1 & 2 pass again (repeatable restore)
#
# Requires: docker (Compose v2) + curl. Deliberately does NOT depend on jq — JSON is parsed
# with sed/grep so the same command works on a minimal Git-Bash and a CI runner.
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
  local i
  for i in $(seq 1 150); do
    if curl -sf "$BASE_URL/.well-known/jwks.json" >/dev/null 2>&1; then
      printf '  API healthy after ~%ss\n' "$i"
      return 0
    fi
    sleep 1
  done
  fail "API did not become healthy within 150s. Last logs:" 2>/dev/null || true
  docker compose -f "$COMPOSE_FILE" logs --tail=40 khatm-api 2>/dev/null || true
  fail "API did not become healthy within 150s"
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

check_e2e() {
  bold "Check 2: login -> /api/auth/me -> issue -> verify (authenticated end-to-end)"

  # 2a. Login is CSRF-exempt + permitAll; the response sets the KHATM_SESSION cookie.
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
    "$BASE_URL/api/auth/login")"
  [ "$code" = "200" ] || fail "login returned HTTP $code (expected 200)"

  # 2b. GET /api/auth/me: proves the session, AND forces the XSRF-TOKEN cookie to be written
  #     (Spring Security 6 resolves the CSRF token lazily; a pure JSON API never triggers it,
  #     so the KH-0.6b console pattern of read-cookie/send-header needs this nudge).
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    "$BASE_URL/api/auth/me")"
  [ "$code" = "200" ] || fail "/api/auth/me returned HTTP $code (session not established)"

  local xsrf
  xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR")"
  [ -n "$xsrf" ] || fail "no XSRF-TOKEN cookie after /api/auth/me — CSRF cookie was not written"

  # 2c. Issue a credential (session auth + `issue` scope; admin holds it). Echo the CSRF token.
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

  # 2d. Verify it (public endpoint — no auth). Expect valid:true.
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

bold "=== Phase 1: clean boot ==="
ensure_network
bold "docker compose up -d --build"
docker compose -f "$COMPOSE_FILE" up -d --build
wait_for_api
check_jwks
check_e2e

bold "=== Phase 2: restore-from-zero (down -v, then up on the SAME image) ==="
docker compose -f "$COMPOSE_FILE" down -v >/dev/null
ensure_network   # external network survives `down`; re-check is harmless
bold "docker compose up -d (image cached, volumes recreated)"
docker compose -f "$COMPOSE_FILE" up -d
wait_for_api
check_jwks
check_e2e

bold "ALL SMOKE CHECKS PASSED — boot + end-to-end + repeatable restore."
printf '\nThe stack has been left running. Tear it down with: docker compose -f %s down -v\n' \
  "$COMPOSE_FILE"
