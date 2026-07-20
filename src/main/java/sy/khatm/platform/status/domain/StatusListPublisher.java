package sy.khatm.platform.status.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.key.api.KeySigner;
import sy.khatm.platform.key.api.SignResult;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.IntegrityException;
import sy.khatm.platform.status.persistence.StatusListRepository;

/**
 * Signs and stores the compact JWS status-list artifact (spec FS-1.3 D1) — the single publish
 * routine both {@code StatusListChangedHandler} (event-driven, near-real-time) and {@code
 * StatusListPublishSweepWorker} (periodic catch-up safety net) call, so there is exactly one place
 * that builds the artifact payload and decides whether a publish is actually needed.
 *
 * <p><b>D5's debounce, precisely:</b> {@link #publishIfStale} republishes only when the list has
 * never been published ({@code signedArtifact IS NULL}) or has fallen behind ({@code
 * artifactVersion < version}); reading the row's live state fresh on every call (under the same
 * {@code FOR UPDATE} lock {@code StatusListRevokerService} uses) is what collapses a revocation
 * storm into one republish for the latest version — every dispatch after the first one that already
 * caught the list up finds nothing left to do.
 *
 * <p>Public — unlike most of this package, {@code status.worker.StatusListChangedHandler} and
 * {@code status.worker.StatusListPublishSweepWorker} (different sub-packages of the same module)
 * both call this directly, the same cross-sub-package-same-module rationale {@code
 * CredentialService} documents for itself. Modulith module-privacy (no other module may see this)
 * is enforced structurally by {@code ModulithBoundariesTest}, not by Java visibility (CONVENTIONS
 * §5).
 */
@Service
public class StatusListPublisher {

  private static final Logger log = LoggerFactory.getLogger(StatusListPublisher.class);

  private final StatusListRepository statusLists;
  private final KeySigner keySigner;
  private final AuditService audit;

  StatusListPublisher(StatusListRepository statusLists, KeySigner keySigner, AuditService audit) {
    this.statusLists = statusLists;
    this.keySigner = keySigner;
    this.audit = audit;
  }

  /**
   * Republish {@code statusListId}'s artifact if — and only if — it is stale or has never been
   * published. No-op (and no audit row) when the artifact is already current.
   *
   * @return {@code true} if a new artifact was actually signed and stored
   */
  @Transactional
  public boolean publishIfStale(UUID statusListId) {
    StatusList list = statusLists.findByIdForUpdate(statusListId).orElse(null);
    if (list == null) {
      // The list was referenced by an event/sweep entry that no longer resolves — nothing to do
      // (should not happen in practice; every status_list_id this class ever receives comes from
      // either a StatusListChanged event or a live findStaleIds() row).
      return false;
    }
    boolean neverPublished = list.getSignedArtifact() == null;
    boolean stale = list.getArtifactVersion() < list.getVersion();
    if (!neverPublished && !stale) {
      return false;
    }

    String jws = sign(list);
    list.setSignedArtifact(jws);
    list.setArtifactVersion(list.getVersion());
    list.setPublishedAt(Instant.now());
    statusLists.save(list);

    // DoD #9: the log line and audit detail carry list_code + version only — never the bitstring
    // or the signed artifact itself (SEC §9).
    log.info("status list published list={} version={}", list.getListCode(), list.getVersion());
    audit.record(
        AuditAction.STATUS_LIST_PUBLISHED,
        "status_list",
        list.getListCode(),
        Map.of("version", list.getVersion()));
    return true;
  }

  /**
   * Build and sign the compact JWS artifact (spec FS-1.3 D1): {@code {"list": <list_code>, "ver":
   * <version>, "cap": <capacity>, "bits": <base64url(gzip(bitstring))>, "iat": <epoch>}}. {@code
   * bitstring} is already gzip-compressed in storage ({@link StatusList#getBitstring()}), so {@code
   * bits} is a direct base64url re-encoding — no extra compression step at publish time.
   */
  private String sign(StatusList list) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .claim("list", list.getListCode())
            .claim("ver", list.getVersion())
            .claim("cap", list.getCapacity())
            .claim(
                "bits", Base64.getUrlEncoder().withoutPadding().encodeToString(list.getBitstring()))
            .claim("iat", Instant.now().getEpochSecond())
            .build();
    try {
      SignResult signed = keySigner.sign(claims);
      return signed.jws();
    } catch (JOSEException e) {
      // Same rationale as CredentialService#issue's signing-failure wrap: an internal integrity
      // problem (key module unreachable/misconfigured), not bad input.
      IntegrityException wrapped =
          new IntegrityException(ErrorCode.KH_KEY_0500, "key.signing-failed");
      wrapped.initCause(e);
      throw wrapped;
    }
  }
}
