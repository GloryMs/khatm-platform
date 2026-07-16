package sy.khatm.platform.shared.error;

/**
 * An internal invariant broke — a dependency the platform trusts (signing, storage, an internal
 * data structure) failed in a way the caller cannot fix by changing their request. Typically maps
 * to HTTP 500 — see the thrown {@link ErrorCode#httpStatus()} for the actual status.
 */
public class IntegrityException extends KhatmException {

  /**
   * @param errorCode the registry code identifying this error and its HTTP status
   * @param messageKey the {@code MessageSource} key to resolve the client-facing message from
   * @param args arguments substituted into the resolved message
   */
  public IntegrityException(ErrorCode errorCode, String messageKey, Object... args) {
    super(errorCode, messageKey, args);
  }
}
