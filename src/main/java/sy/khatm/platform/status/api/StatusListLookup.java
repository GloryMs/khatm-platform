package sy.khatm.platform.status.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI for read-only status-list resolution (spec FS-1.3 D6/D7).
 *
 * <p>One of the {@code status} module's cross-module surfaces, alongside {@link
 * StatusListAllocator} and {@link StatusListRevoker}. {@code credential.domain.CredentialService}
 * calls this from {@code verify} to fill the additive {@code statusListChecked}/{@code
 * statusListVersion}/{@code statusListUri} response fields, and {@code
 * credential.domain.ClaimRedemptionService} calls it to resolve the real {@code statusListUri} in
 * {@code ClaimRedeemResponse} (replacing the pre-KH-1.3 placeholder). No row lock — this is a plain
 * read, safe to call on every {@code /verify} request (spec D6: "رخيص محلياً").
 */
public interface StatusListLookup {

  /**
   * Resolve a status list's current version and public URL.
   *
   * @param statusListId the status list to resolve
   * @return the {@link StatusListRef}, or {@link Optional#empty()} only if {@code statusListId}
   *     does not reference an existing row (should not happen in practice — every caller obtains it
   *     from a {@code credential.status_list_id} foreign key)
   */
  Optional<StatusListRef> findRef(UUID statusListId);
}
