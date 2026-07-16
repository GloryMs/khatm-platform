package sy.khatm.platform.shared.error;

/**
 * The request itself is malformed or fails validation, independent of Bean Validation's own {@code
 * MethodArgumentNotValidException} path (which {@code GlobalExceptionHandler} handles separately,
 * producing the same envelope shape). Typically maps to HTTP 400 — see the thrown {@link
 * ErrorCode#httpStatus()} for the actual status.
 */
public class ValidationException extends KhatmException {

  /**
   * @param errorCode the registry code identifying this error and its HTTP status
   * @param messageKey the {@code MessageSource} key to resolve the client-facing message from
   * @param args arguments substituted into the resolved message
   */
  public ValidationException(ErrorCode errorCode, String messageKey, Object... args) {
    super(errorCode, messageKey, args);
  }
}
