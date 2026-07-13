/**
 * Status module — credential status list management.
 *
 * <p><b>Responsibilities:</b> maintain per-credential status bits in a compact Status List 2021
 * bitstring, expose the status list endpoint, publish status-change events to the ledger.
 *
 * <p><b>Exposed API:</b> {@code api/} sub-package — {@link
 * sy.khatm.platform.status.api.StatusListAllocator#allocate} atomically reserves a {@code
 * (status_list_id, status_idx)} pair for a credential being issued (KH-0.2.1).
 *
 * <p><b>Published events:</b> {@code CredentialStatusChanged} (future — KH-1.3)
 *
 * <p><b>Tables owned:</b> {@code status_list}
 *
 * <p><b>Status:</b> minimal persistence + atomic bit allocation (KH-0.2.1); bitstring publication,
 * revoke-time bit flipping, capacity rollover deferred to KH-1.3.
 */
package sy.khatm.platform.status;
