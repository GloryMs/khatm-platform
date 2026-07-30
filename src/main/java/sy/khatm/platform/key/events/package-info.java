/**
 * Domain events published by the key module and externalized to Redis Streams via Spring Modulith's
 * transactional outbox (ADR-09).
 *
 * <p><b>Published:</b> {@link sy.khatm.platform.key.events.KeyRotated} (KH-2.3a, spec FS-2.3 D3) —
 * fired inside {@code KeyLifecycleService#rotate}'s transaction, routed to the existing {@code
 * khatm.credential.events} stream. Consumed by {@code status.worker.KeyRotationHandler} — {@code
 * key} itself has no dependency on {@code status} and is unaware of who, if anyone, consumes this.
 *
 * <p>A {@code @NamedInterface}, like {@code key.api}: this is the one other sub-package of {@code
 * key} another module may reference — specifically to name the event class when registering a
 * {@code shared.events.StreamEventHandler}, the same cross-module "listen to a published event"
 * shape {@code status.events}/{@code credential.events} already establish for their own listeners
 * (there, always same-module; here, the first genuinely cross-module case).
 *
 * <p>Every event payload here is proof-shaped: ids and timestamps only, never key material (SEC §9
 * — streams and the DLQ obey the same no-disclosure rule as logs).
 */
@org.springframework.modulith.NamedInterface("events")
package sy.khatm.platform.key.events;
