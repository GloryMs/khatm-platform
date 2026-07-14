package sy.khatm.platform.holder.api;

/**
 * SPI for resolving pseudonymous holders by reference.
 *
 * <p>This is the only cross-module surface of the {@code holder} module. Other modules that need a
 * {@code holder_id} depend on this interface, never on the {@code holder} module's internal
 * entities.
 */
public interface HolderDirectory {

  /**
   * Return the holder matching {@code pseudoRef} for the current tenant, registering it first if it
   * does not yet exist.
   *
   * @param pseudoRef pseudonymous holder reference from the issuing organisation's own system;
   *     never a real name or national ID (P1 rule); must not be {@code null} or blank
   * @return an opaque reference to the (possibly newly registered) holder
   */
  HolderRef ensureHolder(String pseudoRef);
}
