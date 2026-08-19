package sy.khatm.platform.rbac.domain;

import java.util.UUID;
import sy.khatm.platform.shared.LocalizedText;

/**
 * One tenant's own counters within an {@link OrgReportView} (KH-2.6b, spec FS-2.5 §4) — one entry
 * per tenant in the calling {@code org:admin}'s full descendant subtree, transitively (children
 * <em>and</em> grandchildren, spec §7), each carrying only its own direct counters (never a
 * cumulative subtree sum — {@link OrgReportView#rollup} is where the whole-tree total lives).
 *
 * @param tenantId the descendant tenant's id
 * @param tenantSlug the descendant tenant's machine slug
 * @param nameI18n the descendant tenant's bilingual display name
 * @param counters this tenant's own counters for the report window
 */
public record OrgReportEntry(
    UUID tenantId, String tenantSlug, LocalizedText nameI18n, OrgReportCounters counters) {}
