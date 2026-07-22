package sy.khatm.platform.consumer.api;

import java.util.UUID;

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

  /**
   * Whether {@code partyId} is permitted to consume credentials issued against {@code schemaId}
   * (KH-1.4.3, spec SEC §7) — backed by the {@code consuming_party_schema} join table.
   *
   * <p>Deny-by-default: a party with no {@code consuming_party_schema} row for this schema is
   * denied, exactly the same outcome as a party with no rows at all — there is no separate "empty
   * allowlist" representation to check.
   *
   * @param partyId the consuming party's id; must not be {@code null}
   * @param schemaId the schema to check; must not be {@code null}
   * @return {@code true} if {@code partyId} is scoped to {@code schemaId}
   */
  boolean isSchemaAllowed(UUID partyId, UUID schemaId);

  /**
   * Scope {@code partyId} to consume credentials issued against {@code schemaId} (KH-1.4.3) —
   * idempotent; allowing the same pair twice has no additional effect.
   *
   * @param partyId the consuming party to scope; must already exist ({@link #ensure})
   * @param schemaId the schema to allow
   */
  void allowSchema(UUID partyId, UUID schemaId);

  /**
   * Whether {@code partyId} is currently {@code ACTIVE} (KH-1.4.4 D4) — a {@code SUSPENDED} party's
   * API keys must fail authentication, exactly like a revoked key. {@code rbac}'s API-key
   * verification consults this on every {@code CONSUMING_PARTY}-key request.
   *
   * @param partyId the consuming party's id; must not be {@code null}
   * @return {@code true} if the party exists and is {@code ACTIVE}; {@code false} if it is {@code
   *     SUSPENDED} or does not exist
   */
  boolean isActive(UUID partyId);
}
