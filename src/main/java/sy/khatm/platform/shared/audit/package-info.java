/**
 * The platform's single {@code audit_log} write path (spec FS-0.6b D8, SEC §9.4/§9.7, NFR-08).
 *
 * <p>{@link sy.khatm.platform.shared.audit.AuditService#record} is the only place any module may
 * write an {@code audit_log} row — direct {@code INSERT INTO audit_log} statements (the KH-0.5 /
 * ADR-09-worker minimal stopgap form) are migrated to call this instead, and an architectural test
 * ({@code NoDirectAuditLogInsertTest}) fails the build if a new one appears anywhere outside this
 * package. {@link sy.khatm.platform.shared.audit.AuditAction} is the closed catalog of event types
 * (spec FS-0.6b §6); {@link sy.khatm.platform.shared.audit.AuditPrincipal} is the small SPI {@code
 * rbac}'s Spring Security principal types implement so {@code AuditService} can attribute a row to
 * {@code USER}/{@code API_KEY} without {@code shared} ever depending on {@code rbac} (the same
 * inversion {@code shared.events.StreamEventHandler} already uses for the ADR-09 consumer SPI).
 *
 * <p>A {@code @NamedInterface} ({@code shared :: audit}) — every other module that writes audit
 * events ({@code credential}, {@code key}, {@code rbac}) depends on exactly this slice of {@code
 * shared}, never on {@code shared.audit.AuditLogEntry}/{@code AuditLogRepository} (both
 * package-private — the entity and its repository are this package's own implementation detail).
 *
 * <p><b>Tables owned:</b> {@code audit_log} (append-only; declared in {@code V1__baseline.sql}, the
 * write path landing here in KH-0.6b).
 */
@org.springframework.modulith.NamedInterface("audit")
package sy.khatm.platform.shared.audit;
