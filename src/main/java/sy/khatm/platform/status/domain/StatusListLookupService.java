package sy.khatm.platform.status.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.status.api.StatusListArtifact;
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
  private final StatusListPublisher publisher;

  StatusListLookupService(
      StatusListRepository statusLists,
      StatusListUriBuilder uriBuilder,
      StatusListPublisher publisher) {
    this.statusLists = statusLists;
    this.uriBuilder = uriBuilder;
    this.publisher = publisher;
  }

  @Override
  public Optional<StatusListRef> findRef(UUID statusListId) {
    return statusLists
        .findById(statusListId)
        .map(list -> new StatusListRef(list.getVersion(), uriBuilder.build(list.getListCode())));
  }

  @Override
  public Optional<StatusListArtifact> findArtifact(UUID tenantId, String listCode) {
    StatusList list = statusLists.findByTenantIdAndListCode(tenantId, listCode).orElse(null);
    if (list == null) {
      return Optional.empty();
    }
    // Lazy publish: a freshly allocated list the worker sweep hasn't reached yet is still servable
    // inline (spec FS-1.3 D3's fallback, moved here from status.web.StatusListController — a
    // controller has no business reaching into StatusListRepository/StatusListPublisher directly,
    // CONVENTIONS §4).
    if (list.getSignedArtifact() == null) {
      publisher.publishIfStale(list.getId());
      list = statusLists.findById(list.getId()).orElseThrow();
    }
    return Optional.of(new StatusListArtifact(list.getSignedArtifact(), list.getVersion()));
  }
}
