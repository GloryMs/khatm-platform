# shared/events — ADR-09 async backbone

The infrastructure for Spring Modulith externalized events → transactional outbox → Redis Streams,
and the worker-side consumer group that drains them. This is a sub-package of the `shared` module
(not a separate Modulith module); the runtime `api`/`worker` roles are a profile split from one
image, not a module boundary.

## Publish side (both roles)

`RedisStreamsExternalizationConfig` registers a `DelegatingEventExternalizer` whose delegate
`XADD`s every `@Externalized` event to its target Redis Stream. There is **no official
`spring-modulith-events-redis` for Modulith 1.2.x** (only amqp/kafka/jms/aws-sqs/aws-sns), so this
provides the same shape those completions do, for Redis Streams. The `event_publication` outbox
(from `spring-modulith-starter-jdbc`) records each event in the publishing transaction; this
externalizer fires after commit and marks the row complete when its future completes successfully
(at-least-once: a failed future leaves the row for replay).

Gated by `khatm.events.externalize` (default `true`); the `test` profile sets it `false` so the
Redis-less test suite never attempts an `XADD`.

## Consume side (worker role only — `khatm.worker.enabled=true`)

- `RedisStreamConsumer` ensures the `khatm-workers` consumer group exists on
  `khatm.credential.events`, then polls on a schedule (`khatm.worker.stream.poll-interval-ms`,
  default 2000 ms) and hands each entry to `StreamEventDispatcher`.
- `StreamEventDispatcher` does idempotent dispatch: de-duplicates by stream entry id
  (`khatm:processed:{stream}:{entryId}`, 24 h TTL), retries the handler up to
  `khatm.worker.stream.max-attempts` (default 3), and on exhaustion copies the entry to the
  dead-letter stream **`khatm.dlq`** and ACKs the original.
- Modules register handlers by implementing `StreamEventHandler` (the `events` named interface).

## Events on the wire

| Event | Stream | Consumed by (MVP) |
|---|---|---|
| `CredentialIssued` (ref, claimCodeExpiresAt, occurredAt) | `khatm.credential.events` | _(none yet — backbone proven by tests; future: status publishing, notifications)_ |

Every event payload is **proof-shaped** — refs and timestamps only, never claim values, disclosures,
salts, or PII (SEC §9 applies to the stream and the DLQ exactly as to logs).

## Dead-letter stream (`khatm.dlq`) — inspection

Entries land here after `max-attempts` handler failures. Each carries `type`, `payload`,
`originStream`, `originId`, and `error`.

Inspect with `redis-cli` against the broker:

```sh
# count entries awaiting attention
XLEN khatm.dlq

# read them all (oldest → newest)
XRANGE khatm.dlq - +

# tail the latest 10
XREVRANGE khatm.dlq + - COUNT 10
```

Replay (manual, once the underlying cause is fixed): re-`XADD` the entry's `payload` to its
`originStream`, then `XDEL khatm.dlq <id>` to drop it from the DLQ. There is no automatic
requeue-from-DLQ in MVP — by design, a human inspects a poisoned message before reprocessing it.

## Configuration (`application.yml`, `khatm.worker.*`)

| Property | Default | Purpose |
|---|---|---|
| `khatm.worker.enabled` | `false` | worker role on (consumers + scheduled jobs) |
| `khatm.worker.stream.poll-interval-ms` | `2000` | consumer poll cadence |
| `khatm.worker.stream.max-attempts` | `3` | handler failures before DLQ |
| `khatm.worker.stream.group` | `khatm-workers` | Redis Streams consumer group |
| `khatm.worker.stream.credential-events-stream` | `khatm.credential.events` | the credential-events stream |
| `khatm.worker.stream.dlq-stream` | `khatm.dlq` | dead-letter stream |
| `khatm.worker.claim-code.expiry-sweep-ms` | `300000` (5 min) | `claim_code` expiry sweep (see `credential/worker`) |

## Not yet built (documented gaps)

- Cross-instance pending reclaim via `XAUTOCLAIM` for crash-recovery (the synchronous-retry + DLQ +
  idempotency covers the stated at-least-once + DLQ semantics; an entry orphaned in a crashed
  consumer's pending list is reclaimed only by that consumer restarting with the same name).
- No automatic DLQ requeue — by design (see above).
- No real business consumer of `CredentialIssued` yet; the round-trip / idempotency / DLQ tests
  register test handlers to prove the backbone.
