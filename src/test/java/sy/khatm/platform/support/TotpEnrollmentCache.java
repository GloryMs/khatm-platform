package sy.khatm.platform.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, JVM-lifetime cache of {@code username -> Base32 TOTP secret} for HTTP test fixtures that
 * transparently enroll TOTP for a shared-context user (spec FS-2.2 V1's mandatory-2FA gate would
 * otherwise wall off almost every existing scope-gate test — see {@code rbac.SessionTestSupport}'s
 * own Javadoc for the full rationale).
 *
 * <p>Deliberately shared across packages (not private to {@code rbac.SessionTestSupport}): {@code
 * db.CrossTenantIsolationTest} extends {@code rbac.RbacHttpTestSupport} and therefore reuses the
 * exact same cached {@code ApplicationContext}/database as every {@code rbac} scope-gate test — if
 * each package tracked its own, separate cache, whichever one enrolled the shared bootstrap admin's
 * TOTP secret <em>first</em> would leave the other unable to complete that user's next login
 * challenge at all.
 */
public final class TotpEnrollmentCache {

  public static final Map<String, String> SECRETS = new ConcurrentHashMap<>();

  private TotpEnrollmentCache() {}
}
