package sy.khatm.platform.shared.error;

/**
 * The request conflicts with the resource's current state. Typically maps to HTTP 409 — see the
 * thrown {@link ErrorCode#httpStatus()} for the actual status.
 */
public class ConflictException extends KhatmException {

  /**
   * @param errorCode the registry code identifying this error and its HTTP status
   * @param messageKey the {@code MessageSource} key to resolve the client-facing message from
   * @param args arguments substituted into the resolved message
   */
  public ConflictException(ErrorCode errorCode, String messageKey, Object... args) {
    super(errorCode, messageKey, args);
  }
}
