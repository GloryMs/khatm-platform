-- V3__status_list_artifact.sql — KH-1.3 (spec FS-1.3 D4): additive-only, V1/V2 untouched.
--
-- Publishes the real signed bitstring artifact per status list: signed_artifact stores the
-- compact JWS itself (D1), artifact_version tracks which status_list.version it was built from
-- (the worker's D5 catch-up condition compares artifact_version < version). The existing
-- signed_artifact_ref column (V1) remains the Phase-2 external-storage pointer — nullable and
-- unused until KH-2.5 (object storage/CDN) fills it in.

ALTER TABLE status_list
  ADD COLUMN signed_artifact  text,
  ADD COLUMN artifact_version bigint NOT NULL DEFAULT 0;
