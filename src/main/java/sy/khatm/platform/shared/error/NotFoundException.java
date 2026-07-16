package sy.khatm.platform.shared.error;

/**
 * A requested resource does not exist. Typically maps to HTTP 404 — see the thrown {@link
 * ErrorCode#httpStatus()} for the actual status.
 */
public class NotFoundException extends KhatmException {

  /**
   * @param errorCode the registry code identifying this error and its HTTP status
   * @param messageKey the {@code MessageSource} key to resolve the client-facing message from
   * @param args arguments substituted into the resolved message
   */
  public NotFoundException(ErrorCode errorCode, String messageKey, Object... args) {
    super(errorCode, messageKey, args);
  }
}
