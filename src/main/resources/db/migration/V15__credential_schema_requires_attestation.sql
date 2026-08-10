-- V15__credential_schema_requires_attestation.sql — KH-2.4-BE (spec FS-2.4 item 1).
--
-- Attested-document support: a schema can require a human-attested scan (the non-automated
-- issuer portal flow) before a credential may be issued against it. Additive-only, defaulting to
-- false so every existing schema and its issuance path is byte-for-byte unaffected.

ALTER TABLE credential_schema ADD COLUMN requires_attestation boolean NOT NULL DEFAULT false;
