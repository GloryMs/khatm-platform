/**
 * RBAC module — role-based access control for the Khatm platform.
 *
 * <p><b>Responsibilities:</b> define roles and scopes ({@code issue}, {@code verify}, {@code
 * consume}, {@code revoke}, {@code admin} — WBS KH-2.2.1), evaluate access decisions for REST
 * endpoints and inter-module operations, manage console-user accounts and role assignments.
 *
 * <p><b>Exposed API:</b> (none yet — KH-0.6 / KH-2.x)
 *
 * <p><b>Published events:</b> (none yet)
 *
 * <p><b>Tables owned:</b> {@code app_user}, {@code role}, {@code user_role}. The KH-0.2.1 baseline
 * migration creates all three and seeds the default tenant's {@code PLATFORM_ADMIN}, {@code
 * TENANT_ADMIN}, {@code ISSUER_OPERATOR} roles; no Java entities exist yet because no code reads or
 * writes these tables until KH-0.6 wires console auth.
 *
 * <p><b>Status:</b> stub — Java implementation deferred to KH-0.6 / KH-2.x.
 */
@org.springframework.modulith.ApplicationModule
package sy.khatm.platform.rbac;
