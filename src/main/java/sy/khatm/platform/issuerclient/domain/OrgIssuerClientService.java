package sy.khatm.platform.issuerclient.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import sy.khatm.platform.shared.LocalizedText;
import sy.khatm.platform.shared.OnBehalfOfExecutor;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.tenant.api.TenantDirectory;
import sy.khatm.platform.tenant.api.TenantRef;

/**
 * The parent-organisation plane for issuer clients (spec FS-2.7a D10): an {@code org:admin} of a
 * parent tenant manages the issuer clients of its <em>direct</em> children, through the existing
 * {@link OnBehalfOfExecutor#runAsChildOrg} mechanism — no parallel one.
 *
 * <p>{@link #requireDirectChild} resolves the path id against {@link
 * TenantDirectory#directChildren} of the caller's own ambient tenant, and collapses "no such
 * tenant", "not my child", "a grandchild", and "someone else's tenant" into the single {@code
 * KH-ORG-0404} — an org admin must not be able to tell them apart. {@link
 * OnBehalfOfExecutor#runAsChildOrg} then re-checks {@code org:admin}, writes the parent-side {@code
 * ORG_ON_BEHALF_OF} marker, and switches the tenant context; the lifecycle's own audit rows land
 * under the child (the "dual audit" of KH-2.6). Because the child is never a root, a client created
 * here never generates a holder secret.
 */
@Service
public class OrgIssuerClientService {

  private final TenantDirectory tenants;
  private final OnBehalfOfExecutor onBehalfOf;
  private final IssuerClientLifecycle lifecycle;

  OrgIssuerClientService(
      TenantDirectory tenants, OnBehalfOfExecutor onBehalfOf, IssuerClientLifecycle lifecycle) {
    this.tenants = tenants;
    this.onBehalfOf = onBehalfOf;
    this.lifecycle = lifecycle;
  }

  /** The child's issuer clients. */
  public List<IssuerClientView> list(UUID childId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), lifecycle::list);
  }

  /** Create a client in the child. */
  public CreatedIssuerClient create(
      UUID childId, LocalizedText name, List<UUID> allowedSchemaIds, Instant expiresAt) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(
        child.id(), child.slug(), () -> lifecycle.create(name, allowedSchemaIds, expiresAt));
  }

  /** Rotate one of the child's clients. */
  public CreatedIssuerClient rotate(UUID childId, UUID clientId, Integer retireAfterHours) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(
        child.id(), child.slug(), () -> lifecycle.rotate(clientId, retireAfterHours));
  }

  /** Suspend one of the child's clients. */
  public IssuerClientView suspend(UUID childId, UUID clientId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), () -> lifecycle.suspend(clientId));
  }

  /** Resume one of the child's clients. */
  public IssuerClientView resume(UUID childId, UUID clientId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), () -> lifecycle.resume(clientId));
  }

  /** Revoke one of the child's clients. */
  public IssuerClientView revoke(UUID childId, UUID clientId) {
    TenantRef child = requireDirectChild(childId);
    return onBehalfOf.runAsChildOrg(child.id(), child.slug(), () -> lifecycle.revoke(clientId));
  }

  private TenantRef requireDirectChild(UUID childId) {
    return tenants.directChildren(TenantContext.current()).stream()
        .filter(child -> child.id().equals(childId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_ORG_0404, "org.child-not-found"));
  }
}
