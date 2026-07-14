#!/usr/bin/env bash
#
# KH-0.2.2 — append-only migration discipline, CI-prep layer.
#
# Standalone re-implementation of MigrationImmutabilityTest's check, for use as a fast,
# JVM-free pre-flight step in a future CI pipeline (KH-0.3.1 wires the actual GitHub Actions
# workflow — this script only needs to be invoked as one step of it, e.g.:
#   - run: ./scripts/check-migration-checksums.sh
# ). Kept in exact sync with the Java test's three failure modes: MODIFIED, DELETED,
# UNREGISTERED. If you change the policy, update both this script and
# src/test/java/sy/khatm/platform/db/MigrationImmutabilityTest.java.
#
# Usage: scripts/check-migration-checksums.sh
# Exit code: 0 if the lock file and migration directory agree, 1 otherwise (with violations
# printed to stderr).

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
lock_file="$repo_root/db/migration-checksums.lock"
migrations_dir="$repo_root/src/main/resources/db/migration"

if [[ ! -f "$lock_file" ]]; then
  echo "ERROR: lock file not found: $lock_file" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  echo "ERROR: neither sha256sum nor shasum is available on PATH" >&2
  exit 1
fi

declare -A locked_hash
while IFS=$'\t' read -r filename hash; do
  [[ -z "$filename" || "$filename" == \#* ]] && continue
  locked_hash["$filename"]="$hash"
done <"$lock_file"

violations=()

for filename in "${!locked_hash[@]}"; do
  expected="${locked_hash[$filename]}"
  file="$migrations_dir/$filename"
  if [[ ! -f "$file" ]]; then
    violations+=("DELETED: migration '$filename' is in db/migration-checksums.lock (checksum $expected) but the file no longer exists under src/main/resources/db/migration/. Deleting an applied migration is forbidden — see docs/CONVENTIONS.md 'Migrations are append-only'.")
    continue
  fi
  actual="$(sha256 "$file")"
  if [[ "$actual" != "$expected" ]]; then
    violations+=("MODIFIED: migration '$filename' was edited after being applied. Locked checksum $expected, actual checksum $actual. Migrations are append-only (CLAUDE.md 'Database rules': \"Never edit an applied migration — append a new one\") — revert this edit and add a NEW migration file instead.")
  fi
done

if [[ -d "$migrations_dir" ]]; then
  for file in "$migrations_dir"/*.sql; do
    [[ -e "$file" ]] || continue
    filename="$(basename "$file")"
    if [[ -z "${locked_hash[$filename]+set}" ]]; then
      actual="$(sha256 "$file")"
      violations+=("UNREGISTERED: migration '$filename' has no entry in db/migration-checksums.lock. Adding a NEW migration requires adding its checksum line. Add this line to db/migration-checksums.lock, then re-run:
	$filename	$actual")
    fi
  done
fi

if [[ ${#violations[@]} -gt 0 ]]; then
  echo "migration lock violations:" >&2
  for v in "${violations[@]}"; do
    echo "- $v" >&2
  done
  exit 1
fi

echo "OK: all migration checksums match db/migration-checksums.lock"
