# Error Codes

> Generated from `ErrorCode` by `ErrorCodesDocGenerationTest` — never hand-edited
> (CLAUDE.md work rule 1). After adding/changing a code, run `mvn test`; on failure the
> exact content to paste in here is printed in the assertion message.

| Code | HTTP Status | Message Key |
|---|---|---|
| `KH-CRD-0404` | 404 | `credential.not-found` |
| `KH-CRD-0409` | 409 | `credential.not-claimable` |
| `KH-KEY-0500` | 500 | `key.signing-failed` |
| `KH-KEY-0404` | 404 | `key.not-found` |
| `KH-KEY-0409` | 409 | `key.not-retiring` |
| `KH-KEY-0422` | 422 | `key.retiring-too-young` |
| `KH-KEY-0400` | 400 | `key.unknown-provider` |
| `KH-KEY-0503` | 503 | `key.provider-unavailable` |
| `KH-SYS-0400` | 400 | `validation.failed` |
| `KH-SYS-0500` | 500 | `system.unexpected-error` |
| `KH-RBC-0401` | 401 | `error.rbc.unauthenticated` |
| `KH-RBC-1401` | 401 | `error.rbc.api_key_invalid` |
| `KH-RBC-0403` | 403 | `error.rbc.forbidden` |
| `KH-SCH-0404` | 404 | `schema.not-found` |
| `KH-CLM-0404` | 404 | `error.clm.invalid_or_expired` |
| `KH-CLM-0429` | 429 | `error.clm.throttled` |
| `KH-STS-0404` | 404 | `status.not-found` |
| `KH-CNS-0403` | 403 | `consumer.schema-not-allowed` |
| `KH-CNS-0400` | 400 | `consumer.invalid-code` |
| `KH-CNS-0404` | 404 | `consumer.party-not-found` |
| `KH-CNS-1404` | 404 | `consumer.allowlist-schema-not-found` |
| `KH-CNS-0409` | 409 | `consumer.duplicate-code` |
| `KH-SCH-0400` | 400 | `schema.validation-failed` |
| `KH-SCH-0409` | 409 | `schema.immutable-after-publish` |
| `KH-SCH-1409` | 409 | `schema.invalid-transition` |
| `KH-CRD-0400` | 400 | `credential.bulk-validation-failed` |
| `KH-TNT-0400` | 400 | `tenant.invalid-slug` |
| `KH-TNT-0404` | 404 | `tenant.not-found` |
| `KH-TNT-0409` | 409 | `tenant.duplicate-slug` |
| `KH-USR-0400` | 400 | `user.validation-failed` |
| `KH-USR-0403` | 403 | `user.must-change-password` |
| `KH-USR-0404` | 404 | `user.not-found` |
| `KH-USR-0409` | 409 | `user.duplicate-username` |
| `KH-USR-0423` | 409 | `user.last-admin` |
| `KH-USR-1403` | 403 | `user.totp-required` |
| `KH-USR-1409` | 409 | `user.totp-conflict` |
