package sy.khatm.platform.status.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.status.api.StatusListRef;
import sy.khatm.platform.status.api.StatusListRevoker;
import sy.khatm.platform.status.events.StatusListChanged;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Default {@link StatusListRevoker} implementation.
 *
 * <p>This class is module-private. External code must depend on {@link StatusListRevoker}, not this
 * class.
 */
@Service
class StatusListRevokerService implements StatusListRevoker {

  private final StatusListRepository statusLists;
  private final StatusListUriBuilder uriBuilder;
  private final ApplicationEventPublisher eventPublisher;

  StatusListRevokerService(
      StatusListRepository statusLists,
      StatusListUriBuilder uriBuilder,
      ApplicationEventPublisher eventPublisher) {
    this.statusLists = statusLists;
    this.uriBuilder = uriBuilder;
    this.eventPublisher = eventPublisher;
  }

  @Override
  @Transactional
  public StatusListRef revoke(UUID statusListId, int idx) {
    // D3: the row lock is what makes two concurrent revokes on the same list, different
    // credentials, never lose an update — the second caller waits for the first to commit its
    // bit flip + version bump before it can read-modify-write its own.
    StatusList list =
        statusLists
            .findByIdForUpdate(statusListId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "status_list "
                            + statusListId
                            + " referenced by a credential's status_list_id FK should always"
                            + " exist"));

    list.setBitstring(BitstringCodec.flipBit(list.getBitstring(), idx));
    list.setVersion(list.getVersion() + 1);
    statusLists.save(list);

    // D3: "then" — published inside this same transaction; ADR-09's outbox captures it now and
    // externalizes it after commit, so the worker consumer only ever sees a bit flip that has
    // genuinely landed. Proof-shaped payload (id + version + timestamp only, never the bitstring
    // itself — SEC §9 applies to the stream exactly as to logs).
    eventPublisher.publishEvent(
        new StatusListChanged(list.getId(), list.getVersion(), Instant.now()));

    return new StatusListRef(list.getVersion(), uriBuilder.build(list.getListCode()));
  }
}
