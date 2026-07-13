/**
 * Ledger module — Merkle proof support over the platform's audit trail.
 *
 * <p><b>Responsibilities:</b> compute and expose Merkle inclusion proofs for audit events and
 * consumption records. The append-only {@code audit_log} table itself is owned by {@code shared}
 * (it is written to from every module); this module builds Merkle trees over it.
 *
 * <p><b>Exposed API:</b> (none yet — KH-1.x)
 *
 * <p><b>Published events:</b> (none — ledger is a sink)
 *
 * <p><b>Tables owned:</b> {@code merkle_node} (Phase 3 — explicitly out of scope for the KH-0.2.1
 * baseline schema, spec FS-0.2 §4).
 *
 * <p><b>Status:</b> stub — implementation deferred to Phase 3.
 */
package sy.khatm.platform.ledger;
