package sy.khatm.platform.status.api;

import java.util.UUID;

/**
 * A single bit allocated on a status list for one credential.
 *
 * @param statusListId the status list the bit belongs to; used as {@code credential.status_list_id}
 * @param idx the allocated bit index; used as {@code credential.status_idx}
 */
public record StatusAllocation(UUID statusListId, int idx) {}
