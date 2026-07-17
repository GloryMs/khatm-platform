/**
 * Public API surface of the {@code rbac} module (spec FS-0.6b §3).
 *
 * <p>{@link sy.khatm.platform.rbac.api.CurrentActor} + {@link
 * sy.khatm.platform.rbac.api.CurrentActorResolver} are the only cross-module surface today — a
 * forward-looking way for a future module to ask "who is making this call" without depending on
 * Spring Security types. All other sub-packages of {@code sy.khatm.platform.rbac} are
 * module-private.
 */
@org.springframework.modulith.NamedInterface("api")
package sy.khatm.platform.rbac.api;
