/**
 * Connector module — outbound webhook and integration connectors.
 *
 * <p><b>Responsibilities:</b> deliver platform events (credential issued, consumed, revoked) to
 * external subscriber endpoints via webhooks; manage connector configuration and retry logic.
 *
 * <p><b>Exposed API:</b> (none yet — KH-1.x)
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code webhook_subscription}, {@code webhook_delivery}
 *
 * <p><b>Status:</b> stub — implementation deferred to KH-1.x.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.connector;
