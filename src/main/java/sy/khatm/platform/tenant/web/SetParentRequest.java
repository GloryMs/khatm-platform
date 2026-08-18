package sy.khatm.platform.tenant.web;

/**
 * {@code POST /api/v1/admin/tenants/{id}/parent} request body (KH-2.6a, spec FS-2.5 §2).
 *
 * @param parentSlug the new parent's slug, or {@code null}/blank to unlink (making the target
 *     tenant a root)
 */
record SetParentRequest(String parentSlug) {}
