package sy.khatm.platform.rbac.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fixed, three-role catalog every tenant is provisioned with (spec FS-2.2 D5 — "custom roles
 * out of scope"). The single Java source of truth for a tenant's role definitions; {@code
 * V12__seed_tenant_role_catalogs.sql} is its migration-time mirror for tenants that already exist
 * (the two must stay in lockstep — the role-catalog regression test pins the post-onboarding result
 * against these exact values, and {@code db.SeededRoleScopesTest} pins the default tenant's).
 *
 * <p>Scope sets are the granular {@code V10__scope_registry_rescope.sql} values — the retired
 * {@code admin} scope never appears here (clean cut, spec FS-2.2 V3).
 */
final class RoleCatalog {

  private RoleCatalog() {}

  /** One catalog role: code, bilingual name, and granular scope set. */
  record Definition(String code, String nameEn, String nameAr, List<String> scopes) {}

  static final List<Definition> ALL =
      List.of(
          new Definition(
              "PLATFORM_ADMIN",
              "Platform Administrator",
              "مدير المنصة",
              List.of(
                  "issue",
                  "verify",
                  "consume",
                  "revoke",
                  "schema:manage",
                  "consumer:manage",
                  "key:manage",
                  "tenant:admin",
                  "platform:admin")),
          new Definition(
              "TENANT_ADMIN",
              "Tenant Administrator",
              "مدير الجهة",
              List.of(
                  "issue",
                  "verify",
                  "consume",
                  "revoke",
                  "schema:manage",
                  "consumer:manage",
                  "key:manage",
                  "tenant:admin")),
          new Definition(
              "ISSUER_OPERATOR",
              "Issuer Operator",
              "مشغّل الإصدار",
              List.of("issue", "verify", "revoke")));

  /** The catalog's role codes — the only legal values for a user's {@code roles[]} request. */
  static Set<String> codes() {
    return ALL.stream().map(Definition::code).collect(Collectors.toUnmodifiableSet());
  }
}
