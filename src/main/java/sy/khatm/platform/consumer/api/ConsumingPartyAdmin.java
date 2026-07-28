package sy.khatm.platform.consumer.api;

import java.util.List;
import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Admin-plane SPI for managing consuming parties (KH-1.4.4) — the surface behind {@code
 * /api/v1/admin/consuming-parties}, guarded by the {@code consumer:manage} scope (spec FS-2.2 D2).
 *
 * <p>Separate from {@link ConsumingPartyRegistry} (the runtime consume-path SPI: resolve-by-code,
 * schema-scoping checks) because these are console-operator management operations, not something
 * the hot consume path needs. {@code rbac}'s consuming-party key-mint endpoint depends on this
 * interface (via {@link #get}) to validate a party exists before minting a {@code CONSUMING_PARTY}
 * key for it — the one method used across the module boundary.
 */
public interface ConsumingPartyAdmin {

  /**
   * Register a new consuming party. Idempotent by identity — the row's id is derived from {@code
   * (tenant, code)} exactly as {@link ConsumingPartyRegistry#ensure} derives it, so explicit
   * creation and implicit ensure can never produce two rows for one code — but a second explicit
   * create of an already-registered code is a conflict (KH-1.4.4 D2: {@code KH-CNS-0409}), not a
   * silent overwrite.
   *
   * @param code lowercase slug matching {@code ^[a-z0-9][a-z0-9-_]{1,62}$}; invalid → {@code
   *     KH-CNS-0400}
   * @param nameI18n bilingual display name (both {@code en} and {@code ar} required)
   * @return the newly created party's view
   */
  ConsumingPartyView create(String code, LocalizedText nameI18n);

  /**
   * List every consuming party registered for the current tenant, newest first.
   *
   * @return the parties' admin views (each with its resolved schema allowlist)
   */
  List<ConsumingPartyView> list();

  /**
   * Fetch one consuming party by id.
   *
   * @param id the party's id
   * @return the party's admin view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-CNS-0404} if no such party
   *     exists in the current tenant
   */
  ConsumingPartyView get(UUID id);

  /**
   * Suspend a party — its API keys stop authenticating (KH-1.4.4 D4). Idempotent (suspending an
   * already-suspended party is a no-op that still returns the current view).
   *
   * @param id the party to suspend
   * @return the updated view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-CNS-0404} if no such party
   *     exists
   */
  ConsumingPartyView suspend(UUID id);

  /**
   * Reactivate a suspended party — its API keys authenticate again. Idempotent.
   *
   * @param id the party to activate
   * @return the updated view
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-CNS-0404} if no such party
   *     exists
   */
  ConsumingPartyView activate(UUID id);

  /**
   * Scope a party to consume credentials issued against a schema (KH-1.4.4 D5) — idempotent
   * (allowing the same pair twice has no additional effect). Both the party and the schema must
   * exist in the current tenant.
   *
   * @param partyId the party to scope
   * @param schemaId the schema to allow; must exist in-tenant (any non-deleted status)
   * @return the party's updated view, including the newly allowed schema
   * @throws sy.khatm.platform.shared.error.NotFoundException {@code KH-CNS-0404} if the party does
   *     not exist, {@code KH-CNS-1404} if the schema does not exist
   */
  ConsumingPartyView allowSchema(UUID partyId, UUID schemaId);

  /**
   * Remove a schema from a party's allowlist (KH-1.4.4 D5). Idempotent — removing a pair that is
   * not allowed (including for an unknown party) is a successful no-op.
   *
   * @param partyId the party to unscope
   * @param schemaId the schema to disallow
   * @return {@code true} if an allowlist row was actually removed, {@code false} if there was
   *     nothing to remove
   */
  boolean disallowSchema(UUID partyId, UUID schemaId);
}
