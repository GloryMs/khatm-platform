package sy.khatm.platform.status.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.status.api.StatusAllocation;
import sy.khatm.platform.status.api.StatusListAllocator;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Default {@link StatusListAllocator} implementation.
 *
 * <p>This class is module-private. External code must depend on {@link StatusListAllocator}, not
 * this class.
 */
@Service
class StatusListAllocatorService implements StatusListAllocator {

  private static final int DEFAULT_CAPACITY_BITS = 131_072;

  private final StatusListRepository statusLists;

  StatusListAllocatorService(StatusListRepository statusLists) {
    this.statusLists = statusLists;
  }

  @Override
  @Transactional
  public StatusAllocation allocate(String listCode) {
    UUID tenantId = TenantContext.current();
    StatusList list =
        statusLists
            .findWithLockByTenantIdAndListCode(tenantId, listCode)
            .orElseGet(() -> createList(tenantId, listCode));

    // KH-1.3 tracks list capacity/rollover; out of scope for KH-0.2.1's baseline plumbing.
    int idx = list.getNextIdx();
    list.setNextIdx(idx + 1);
    list.setVersion(list.getVersion() + 1);
    statusLists.save(list);
    return new StatusAllocation(list.getId(), idx);
  }

  private StatusList createList(UUID tenantId, String listCode) {
    StatusList list = new StatusList();
    list.setId(Uuidv7.generate());
    list.setTenantId(tenantId);
    list.setListCode(listCode);
    list.setBitstring(emptyGzippedBitstring(DEFAULT_CAPACITY_BITS));
    list.setCapacity(DEFAULT_CAPACITY_BITS);
    list.setNextIdx(0);
    list.setVersion(0);
    list.setCreatedAt(Instant.now());
    return statusLists.save(list);
  }

  private static byte[] emptyGzippedBitstring(int capacityBits) {
    byte[] zeros = new byte[capacityBits / 8];
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
      gzip.write(zeros);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to initialize status list bitstring", e);
    }
    return buffer.toByteArray();
  }
}
