package sy.khatm.platform.rbac.domain;

/**
 * The outcome of {@link AuthService#login} (spec FS-2.2 V1) — either a completed login, or a signal
 * that the user's password was correct but an active TOTP enrollment must still be satisfied via
 * {@link AuthService#completeTotpChallenge} before a session is established.
 *
 * <p>{@code rbac.web.AuthController} pattern-matches on this and maps each case to its own HTTP
 * response shape — a plain {@code 200} with a session cookie for {@link Success} (byte-for-byte the
 * pre-TOTP behavior), a {@code 200} with a small JSON body and <em>no</em> cookie for {@link
 * TotpChallenge}. The wire contract only grows additively (a previously-always-empty body now
 * sometimes carries one field); this internal sealed type itself is free to change shape however
 * needed; it never was the wire contract. No session, cookie, or partial {@code Authentication} is
 * ever created for a {@link TotpChallenge} — the challenge is tracked purely server-side in Redis
 * (keyed by an opaque, single-use id), so an incomplete login never leaves any trace in Spring
 * Security's session machinery.
 *
 * <p>Public (unlike most of {@code rbac.domain}) — {@code rbac.web.AuthController}, a different
 * Java package inside the module-private {@code rbac} module, needs it; same visibility precedent
 * as {@link LoginResult}.
 */
public sealed interface LoginOutcome {

  /** A fully completed login — establish the session exactly as before TOTP existed. */
  record Success(LoginResult result) implements LoginOutcome {}

  /** Password verified; a confirmed TOTP enrollment must still be satisfied. */
  record TotpChallenge(String challengeId) implements LoginOutcome {}
}
