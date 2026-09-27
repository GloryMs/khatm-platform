package sy.khatm.platform.issuerclient.api;

import java.util.Optional;

/**
 * Resolves a presented {@code Bearer khi_...} value to a principal (spec FS-2.7a D2/D3).
 *
 * <p>Exists so {@code rbac.security}'s authentication filter can authenticate issuer clients
 * without ever seeing this module's persistence. Every failure mode collapses to {@link
 * Optional#empty()}; the specific reason is recorded here, in the audit trail, and is never
 * returned (anti-enumeration, D12).
 */
public interface IssuerClientAuthenticator {

  /**
   * Verify a raw key.
   *
   * @param rawKey the full {@code khi_<prefix>_<secret>} value (the part after {@code Bearer })
   * @return the principal for an ACTIVE (or still-in-grace RETIRING), unexpired client whose tenant
   *     is active and whose secret matches; empty for anything else
   */
  Optional<IssuerClientPrincipal> resolve(String rawKey);
}
