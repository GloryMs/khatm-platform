package sy.khatm.platform.rbac.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sy.khatm.platform.rbac.persistence.AppUserRepository;
import sy.khatm.platform.rbac.persistence.UserTotpRecoveryCodeRepository;
import sy.khatm.platform.shared.TenantContext;
import sy.khatm.platform.shared.Uuidv7;
import sy.khatm.platform.shared.audit.AuditAction;
import sy.khatm.platform.shared.audit.AuditService;
import sy.khatm.platform.shared.error.ConflictException;
import sy.khatm.platform.shared.error.ErrorCode;
import sy.khatm.platform.shared.error.NotFoundException;
import sy.khatm.platform.shared.error.ValidationException;

/**
 * TOTP second-factor enrollment, confirmation, verification, and recovery (spec FS-2.2 V1, RFC
 * 6238). The self-service half ({@link #enroll}/{@link #confirm}) is called from {@code
 * rbac.web.UserAdminController} for the current session's own user; the verification half ({@link
 * #verifyLoginCode}/{@link #consumeRecoveryCode}/{@link #hasActiveTotp}) is called from {@link
 * AuthService} to gate/complete the login-challenge step; the admin half ({@link
 * #resetForUserInCurrentTenant}) is called from {@code rbac.web.UserAdminController} and (via
 * {@code OnBehalfOfExecutor}) {@link TenantProvisioningService}.
 *
 * <p><b>Enrollment is idempotent-overwrite while pending, exclusive once confirmed:</b> {@link
 * #enroll} always generates a fresh secret and overwrites any not-yet-confirmed prior enrollment —
 * an abandoned {@code enroll} call needs no separate expiry sweep, since the next {@code enroll}
 * simply supersedes it. {@link #confirm} additionally enforces {@code khatm.auth.totp.enroll-ttl}
 * (default {@code PT10M}) from {@code totp_enrolled_at}: confirming long after enrolling is refused
 * (re-enroll instead) rather than left open indefinitely. Once {@code totp_confirmed_at} is set,
 * {@link #enroll} refuses outright (409) — changing device requires {@link
 * #resetForUserInCurrentTenant} first, a deliberate administrative step for a security-sensitive
 * change, not a silent self-service overwrite of an active factor.
 *
 * <p><b>Recovery codes are plaintext-once</b> (spec FS-2.2, same discipline {@code
 * ApiKeyService}/{@code UserAdminService} apply to keys/temporary passwords): {@link #confirm}
 * returns all 10 in the clear exactly once; only their hash (via the same {@link PasswordEncoder}
 * bean {@code UserAdminService} uses for temporary passwords) is ever persisted.
 *
 * <p>This class is module-private; {@code rbac.web}'s controllers (a different Java package inside
 * the module) and {@link AuthService}/{@link TenantProvisioningService} (this same package) are the
 * only callers.
 */
@Service
public class TotpService {

  private static final String ISSUER = "Khatm";
  private static final int RECOVERY_CODE_COUNT = 10;
  private static final int RECOVERY_CODE_LENGTH = 10;
  private static final String RECOVERY_CODE_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final AppUserRepository users;
  private final UserTotpRecoveryCodeRepository recoveryCodes;
  private final TotpSecretEncryptionService secretEncryption;
  private final PasswordEncoder passwordEncoder;
  private final AuditService audit;
  private final Duration enrollTtl;

  public TotpService(
      AppUserRepository users,
      UserTotpRecoveryCodeRepository recoveryCodes,
      TotpSecretEncryptionService secretEncryption,
      PasswordEncoder passwordEncoder,
      AuditService audit,
      @Value("${khatm.auth.totp.enroll-ttl:PT10M}") Duration enrollTtl) {
    this.users = users;
    this.recoveryCodes = recoveryCodes;
    this.secretEncryption = secretEncryption;
    this.passwordEncoder = passwordEncoder;
    this.audit = audit;
    this.enrollTtl = enrollTtl;
  }

  /**
   * Begin (or restart) enrollment for the current session's own user: generate a fresh secret,
   * encrypt it at rest, and return the secret (Base32) + {@code otpauth://} URI exactly once — this
   * is the one and only moment the raw secret is ever exposed.
   *
   * @param userId the enrolling user (from the session principal, never a request body)
   * @return the plaintext-once secret and enrollment URI
   * @throws NotFoundException {@code KH-USR-0404} if the user does not exist
   * @throws ConflictException {@code KH-USR-1409} if TOTP is already confirmed/active — reset first
   */
  @Transactional
  public TotpEnrollment enroll(UUID userId) {
    AppUser user = requireUser(userId);
    if (user.getTotpConfirmedAt() != null) {
      throw new ConflictException(ErrorCode.KH_USR_1409, "user.totp-conflict", "already-active");
    }
    byte[] secret = TotpAlgorithm.generateSecret();
    user.setTotpSecretEnc(secretEncryption.encrypt(secret));
    user.setTotpEnrolledAt(Instant.now());
    user.setTotpConfirmedAt(null);
    users.save(user);
    String otpAuthUri =
        TotpAlgorithm.buildOtpAuthUri(
            ISSUER, TenantContext.currentSlug() + ":" + user.getUsername(), secret);
    return new TotpEnrollment(TotpAlgorithm.toBase32(secret), otpAuthUri);
  }

  /**
   * Confirm a pending enrollment with a live TOTP code, activating it and minting 10 recovery codes
   * (returned in the clear exactly once).
   *
   * @param userId the confirming user
   * @param code the 6-digit code from the authenticator app
   * @return the 10 plaintext-once recovery codes
   * @throws NotFoundException {@code KH-USR-0404} if the user does not exist
   * @throws ConflictException {@code KH-USR-1409} if there is no pending enrollment, it already
   *     confirmed, or it has expired (re-enroll in any of those cases)
   * @throws ValidationException {@code KH-USR-0400} if {@code code} does not match
   */
  @Transactional
  public List<String> confirm(UUID userId, String code) {
    AppUser user = requireUser(userId);
    if (user.getTotpConfirmedAt() != null) {
      throw new ConflictException(ErrorCode.KH_USR_1409, "user.totp-conflict", "already-active");
    }
    if (user.getTotpSecretEnc() == null || user.getTotpEnrolledAt() == null) {
      throw new ConflictException(
          ErrorCode.KH_USR_1409, "user.totp-conflict", "no-pending-enrollment");
    }
    if (Instant.now().isAfter(user.getTotpEnrolledAt().plus(enrollTtl))) {
      throw new ConflictException(
          ErrorCode.KH_USR_1409, "user.totp-conflict", "enrollment-expired");
    }
    byte[] secret = secretEncryption.decrypt(user.getTotpSecretEnc());
    if (!TotpAlgorithm.verify(secret, code, Instant.now().getEpochSecond())) {
      throw new ValidationException(ErrorCode.KH_USR_0400, "user.validation-failed", "totp-code");
    }
    user.setTotpConfirmedAt(Instant.now());
    users.save(user);
    List<String> plaintextCodes = mintRecoveryCodes(user);
    audit.record(AuditAction.USER_TOTP_ENROLLED, "app_user", user.getUsername(), null);
    return plaintextCodes;
  }

  /**
   * Whether {@code userId} currently has an active (confirmed) TOTP enrollment — used by {@link
   * AuthService} to decide whether login needs the challenge step.
   */
  @Transactional(readOnly = true)
  public boolean hasActiveTotp(UUID userId) {
    return users.findHasActiveTotpById(userId).orElse(false);
  }

  /**
   * Verify a submitted TOTP code for a user already known to have an active enrollment (spec's
   * ±1-time-step drift allowance) — called only from {@link AuthService}'s login-challenge
   * completion, under the challenge's own tenant context.
   *
   * @param userId the user completing the challenge
   * @param code the submitted 6-digit code
   * @return {@code true} if the code matches
   */
  @Transactional(readOnly = true)
  public boolean verifyLoginCode(UUID userId, String code) {
    AppUser user = requireUser(userId);
    if (user.getTotpSecretEnc() == null) {
      return false;
    }
    byte[] secret = secretEncryption.decrypt(user.getTotpSecretEnc());
    return TotpAlgorithm.verify(secret, code, Instant.now().getEpochSecond());
  }

  /**
   * Consume a one-time recovery code, if it matches an unused one for this user.
   *
   * @param userId the user completing the challenge
   * @param recoveryCode the submitted recovery code
   * @return the number of recovery codes remaining, if {@code recoveryCode} matched and was
   *     consumed; empty if it matched none
   */
  @Transactional
  public Optional<Long> consumeRecoveryCode(UUID userId, String recoveryCode) {
    List<UserTotpRecoveryCode> unused = recoveryCodes.findByUserIdAndUsedAtIsNull(userId);
    for (UserTotpRecoveryCode candidate : unused) {
      if (passwordEncoder.matches(recoveryCode, candidate.getCodeHash())) {
        candidate.setUsedAt(Instant.now());
        recoveryCodes.save(candidate);
        return Optional.of(recoveryCodes.countByUserIdAndUsedAtIsNull(userId));
      }
    }
    return Optional.empty();
  }

  /**
   * Administratively clear a user's TOTP enrollment (spec FS-2.2 V1) — the user re-enrolls at next
   * login if a mandatory scope requires it. Idempotent: a user with no TOTP enrolled is a no-op.
   *
   * @param userId the target user, scoped to the current tenant (RLS backstop)
   * @throws NotFoundException {@code KH-USR-0404} if the user does not exist in this tenant
   */
  @Transactional
  public void resetForUserInCurrentTenant(UUID userId) {
    AppUser user = requireUserInCurrentTenant(userId);
    boolean forced = false;
    if (user.getTotpSecretEnc() != null
        || user.getTotpEnrolledAt() != null
        || user.getTotpConfirmedAt() != null) {
      forced = true;
      user.setTotpSecretEnc(null);
      user.setTotpEnrolledAt(null);
      user.setTotpConfirmedAt(null);
      users.save(user);
    }
    recoveryCodes.invalidateAllUnused(userId, Instant.now());
    audit.record(
        AuditAction.USER_TOTP_RESET, "app_user", user.getUsername(), Map.of("hadActive", forced));
  }

  private List<String> mintRecoveryCodes(AppUser user) {
    recoveryCodes.invalidateAllUnused(user.getId(), Instant.now());
    List<String> plaintextCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
    for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
      String plain = generateRecoveryCode();
      plaintextCodes.add(plain);
      UserTotpRecoveryCode row = new UserTotpRecoveryCode();
      row.setId(Uuidv7.generate());
      row.setTenantId(TenantContext.current());
      row.setUserId(user.getId());
      row.setCodeHash(passwordEncoder.encode(plain));
      row.setCreatedAt(Instant.now());
      recoveryCodes.save(row);
    }
    return plaintextCodes;
  }

  private static String generateRecoveryCode() {
    StringBuilder sb = new StringBuilder(RECOVERY_CODE_LENGTH + 1);
    for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
      if (i == RECOVERY_CODE_LENGTH / 2) {
        sb.append('-');
      }
      sb.append(
          RECOVERY_CODE_ALPHABET.charAt(SECURE_RANDOM.nextInt(RECOVERY_CODE_ALPHABET.length())));
    }
    return sb.toString();
  }

  private AppUser requireUser(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_USR_0404, "user.not-found"));
  }

  private AppUser requireUserInCurrentTenant(UUID userId) {
    UUID tenantId = TenantContext.current();
    return users
        .findById(userId)
        .filter(u -> tenantId.equals(u.getTenantId()))
        .orElseThrow(() -> new NotFoundException(ErrorCode.KH_USR_0404, "user.not-found"));
  }
}
