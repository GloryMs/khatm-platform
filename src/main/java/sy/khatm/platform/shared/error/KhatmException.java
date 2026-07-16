package sy.khatm.platform.shared.error;

/**
 * Base of the platform's single exception hierarchy (CLAUDE.md work rule 3).
 *
 * <p>Every thrown business/request error is one of this class's six subtypes — never a raw {@code
 * RuntimeException} (Checkstyle-enforced) and never a silently-swallowed {@code Exception}. {@code
 * GlobalExceptionHandler} is the only place that turns an instance of this class into an HTTP
 * response; nothing else should catch a {@code KhatmException} to build a response by hand (spec
 * FS-0.6a D8).
 *
 * <p>{@code messageKey} + {@code args} are resolved against the request's locale by {@code
 * GlobalExceptionHandler} via {@code MessageSource} — this class itself has no i18n dependency.
 * {@link #getMessage()} returns the raw key (a technical, English-only value suitable for logs);
 * the localized client-facing message is never computed here.
 */
public abstract class KhatmException extends RuntimeException {

  private final ErrorCode errorCode;
  private final String messageKey;
  private final transient Object[] args;

  /**
   * @param errorCode the registry code identifying this error and its HTTP status
   * @param messageKey the {@code MessageSource} key to resolve the client-facing message from
   * @param args arguments substituted into the resolved message (e.g. {@code {0}} placeholders);
   *     may be empty
   */
  protected KhatmException(ErrorCode errorCode, String messageKey, Object... args) {
    super(messageKey);
    this.errorCode = errorCode;
    this.messageKey = messageKey;
    this.args = args.clone();
  }

  /**
   * The registry code identifying this error and its HTTP status.
   *
   * @return the error code
   */
  public ErrorCode errorCode() {
    return errorCode;
  }

  /**
   * The {@code MessageSource} key to resolve the client-facing message from.
   *
   * @return the message key
   */
  public String messageKey() {
    return messageKey;
  }

  /**
   * Arguments substituted into the resolved message.
   *
   * @return a defensive copy of the argument array
   */
  public Object[] args() {
    return args.clone();
  }
}
