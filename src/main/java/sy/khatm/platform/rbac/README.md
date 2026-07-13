# rbac

Role-based access control — console-user accounts, roles, and the five platform scopes
(`issue`, `verify`, `consume`, `revoke`, `admin` — WBS KH-2.2.1).

**Events in:** none. **Events out:** none yet.

**Tables owned:** `app_user`, `role`, `user_role`.

**Status:** stub. The KH-0.2.1 baseline migration creates all three tables and seeds the
default tenant's `PLATFORM_ADMIN`, `TENANT_ADMIN`, `ISSUER_OPERATOR` roles directly via SQL —
no Java entities exist yet because nothing reads or writes these tables until KH-0.6 wires
console authentication and endpoint-level access checks.
