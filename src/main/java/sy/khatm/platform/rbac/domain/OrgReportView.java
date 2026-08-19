package sy.khatm.platform.rbac.domain;

import java.util.List;
import sy.khatm.platform.shared.web.StatsWindow;

/**
 * {@code GET /api/v1/org/reports}'s response (KH-2.6b, spec FS-2.5 §4) — one {@link OrgReportEntry}
 * per tenant in the calling {@code org:admin}'s full descendant subtree (transitive, spec §7), plus
 * {@link #rollup}, the sum across the entire subtree. Counters only — proofs-not-content (P1); no
 * row-level detail of any kind belongs here.
 *
 * @param window the requested/resolved time window
 * @param children one entry per descendant tenant (any depth), each carrying its own counters only
 * @param rollup the sum of every entry in {@link #children} — the whole-subtree total
 */
public record OrgReportView(
    StatsWindow window, List<OrgReportEntry> children, OrgReportCounters rollup) {}
