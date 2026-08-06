# Kafka Listener Implementation Standard — CTRM Platform

You are implementing or reviewing a Kafka listener in the `posval` / CTRM platform.
All listeners MUST follow this pattern. Deviations require ADR justification.

## Non-negotiable rules

1. **Use Spring `@KafkaListener` on a `ConcurrentKafkaListenerContainerFactory`.**
   Do NOT write manual `KafkaConsumer` poll loops. Manual poll loops are banned —
   they reinvent commit strategy, retry, DLQ, and rebalance handling, and get
   them wrong.

2. **`enable.auto.commit = false`.** Ack mode is `MANUAL_IMMEDIATE` (per-record)
   or `RECORD`. Commit ONLY after the DB transaction has committed successfully.
   Auto-commit + exception-swallow = silent data loss. In this domain, silent
   data loss means missing positions, wrong MTM, wrong settlement.

3. **No `try/catch` that swallows exceptions inside the record handler.** Let
   exceptions propagate to the container's `DefaultErrorHandler`. The error
   handler is the ONLY place that decides retry vs DLQ.

4. **Configure `DefaultErrorHandler` with:**
    - Exponential backoff (`ExponentialBackOffWithMaxRetries`, 3–5 attempts).
    - `DeadLetterPublishingRecoverer` publishing to `<original-topic>.DLQ`.
    - Non-retryable exceptions (deserialization, validation) go straight to DLQ.

5. **Idempotency is the handler's responsibility.** Kafka is at-least-once.
   Redelivery WILL happen on rebalance, network hiccup, or retry. Every handler
   must dedupe by domain key (e.g., `tradeId + tradeVersion`, `eventId`).
   Assume duplicates. Never rely on "we only see each message once."

6. **Sizing to avoid `max.poll.interval.ms` blowups:**
    - `max.poll.records` × p99 handler latency must be < `max.poll.interval.ms`
      with 2× margin.
    - Default `max.poll.records=500` is almost always too high for DB-bound
      handlers. Start at 50 and measure.
    - Never do long-running work (>seconds) on the poll thread without pausing
      the partition.

7. **Concurrency = partition count** (or a clean divisor). Set
   `factory.setConcurrency(N)`. Never spawn threads inside the handler — the
   container thread model is what gives you ordering guarantees per partition.

8. **Tenant context** must be set from the event payload BEFORE the transaction
   opens and cleared in a `finally`. Use `ThreadLocalTenantContext`. If work is
   offloaded to another thread, propagate the context explicitly — do not
   assume ThreadLocal survives.

9. **Deserialization**: use a typed `Deserializer` bound in the container
   config, NOT hand-rolled `ObjectMapper.readTree(...)` inside the handler.
   Deserialization failures must trigger a non-retryable path → DLQ.

10. **Graceful shutdown** is the container's job. Do not implement
    `SmartLifecycle`, do not manage `Thread` instances, do not call
    `consumer.wakeup()` yourself.

## Contracts to preserve (do not modify)

These interfaces define the boundary between the Kafka adapter and the domain.
The listener adapts Kafka to these — it does not replace them.

- `com.power.posval.kafka.TradeCapturedConsumer` (and siblings) — domain
  handler. Method signature is `void handle(<Event>)`. Do not add Kafka types
  to this interface.
- `com.power.posval.app.provider.TransactionalExecutor` — wraps handler
  invocation in a DB transaction. Every handler call goes through this.
- `com.power.posval.persistence.tenant.ThreadLocalTenantContext` — set from
  event `tenantId`, cleared in finally.
- `com.power.posval.domain.event.*` — event DTOs. Records, immutable. No
  Jackson annotations should leak into the domain; use mixin config if needed.

## Reference implementation

<!-- FILL THIS IN once you have a canonical good listener -->
Canonical reference: `com.power.posval.app.kafka.<REFERENCE_CLASS>`
Read this class before implementing a new listener. New listeners should
structurally mirror it (config class + `@KafkaListener` bean + error handler +
DLQ topic naming).

## Anti-pattern reference (do NOT imitate)

`com.power.posval.app.kafka.TradeCapturedKafkaListener` (pre-refactor) is
retained in git history as an example of what NOT to do:
- Manual `SmartLifecycle` + poll loop
- Auto-commit with swallowed exceptions → silent data loss
- Hand-rolled JSON tree parsing
- No retry, no DLQ, no idempotency guard

If you find yourself writing code that resembles this file, stop.

## What you must produce for a new listener

1. A `@Configuration` class exposing:
    - `ConsumerFactory<String, <Event>>` with typed deserializer
    - `ConcurrentKafkaListenerContainerFactory` with:
        - `AckMode.MANUAL_IMMEDIATE`
        - `DefaultErrorHandler` with backoff + DLQ recoverer
        - `concurrency` = partition count
2. A `@Component` class with a single `@KafkaListener` method taking
   `ConsumerRecord<String, <Event>>` (or the event directly) + `Acknowledgment`.
3. The listener method:
    - Sets tenant context from event
    - Delegates to domain `Consumer` via `TransactionalExecutor`
    - Acknowledges on success
    - Clears tenant context in `finally`
    - Lets exceptions propagate

## Checklist before opening PR

- [ ] No manual `KafkaConsumer` or poll loop
- [ ] `enable.auto.commit=false` verified in config
- [ ] `DefaultErrorHandler` + DLQ topic wired
- [ ] Handler is idempotent (dedupe key documented)
- [ ] `max.poll.records` sized against p99 handler latency
- [ ] Concurrency matches partition count
- [ ] Tenant context set from payload, cleared in finally
- [ ] Deserialization errors route to DLQ, not retry
- [ ] DLQ topic exists in infra config
- [ ] Integration test covers: happy path, retry-then-success, retry-exhausted → DLQ, duplicate delivery