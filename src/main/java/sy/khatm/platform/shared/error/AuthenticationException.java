package sy.khatm.platform.shared.error;

/**
 * The caller's identity could not be established. Typically maps to HTTP 401 — see the thrown
 * {@link ErrorCode#httpStatus()} for the actual status.
 *
 * <p>Not thrown anywhere yet — session/API-key authentication is KH-0.6b. Defined now so the
 * hierarchy is complete and KH-0.6b only needs to add {@code KH-RBC-*} codes and throw sites, not
 * design a new exception type.
 */
public class AuthenticationException extends KhatmException {

  /**
   * @param errorCode the registry code identifying this error and its HTTP status
   * @param messageKey the {@code MessageSource} key to resolve the client-facing message from
   * @param args arguments substituted into the resolved message
   */
  public AuthenticationException(ErrorCode errorCode, String messageKey, Object... args) {
    super(errorCode, messageKey, args);
  }
}
