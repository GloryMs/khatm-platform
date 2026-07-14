package sy.khatm.platform.consumer.api;

/**
 * SPI for resolving consuming parties (verifiers) by code.
 *
 * <p>This is the only cross-module surface of the {@code consumer} module. The {@code credential}
 * module depends on this interface to obtain a {@code consuming_party_id} when recording a
 * consumption, never on the {@code consumer} module's internal entities.
 */
public interface ConsumingPartyRegistry {

  /**
   * Return the consuming party matching {@code code} for the current tenant, registering it first
   * if it does not yet exist.
   *
   * <p>Real onboarding issues a caller-visible API key and hashes it (KH-1.4.3); this find-or-
   * create path derives a stand-in hash from {@code code} itself so that consumption can be
   * recorded end-to-end before that onboarding flow is built.
   *
   * @param code a stable identifier for the consuming organisation or device; never a real person
   *     name (P1 rule); must not be {@code null} or blank
   * @return an opaque reference to the (possibly newly registered) consuming party
   */
  ConsumingPartyRef ensure(String code);
}
