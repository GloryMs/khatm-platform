package sy.khatm.platform.status.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.status.api.StatusListLookup;
import sy.khatm.platform.status.api.StatusListRef;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Default {@link StatusListLookup} implementation.
 *
 * <p>This class is module-private. External code must depend on {@link StatusListLookup}, not this
 * class.
 */
@Service
class StatusListLookupService implements StatusListLookup {

  private final StatusListRepository statusLists;
  private final StatusListUriBuilder uriBuilder;

  StatusListLookupService(StatusListRepository statusLists, StatusListUriBuilder uriBuilder) {
    this.statusLists = statusLists;
    this.uriBuilder = uriBuilder;
  }

  @Override
  public Optional<StatusListRef> findRef(UUID statusListId) {
    return statusLists
        .findById(statusListId)
        .map(list -> new StatusListRef(list.getVersion(), uriBuilder.build(list.getListCode())));
  }
}
