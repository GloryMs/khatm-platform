package sy.khatm.platform.consumer.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request to register a consuming party (KH-1.4.4 D2, {@code POST
 * /api/v1/admin/consuming-parties}).
 *
 * @param code lowercase slug, {@code ^[a-z0-9][a-z0-9-_]{1,62}$} — validated by the service (a bad
 *     format is {@code KH-CNS-0400}, not a generic Bean-Validation failure, so the caller gets the
 *     dedicated code); the party's id is derived deterministically from {@code (tenant, code)}
 * @param nameI18n bilingual display name; both {@code en} and {@code ar} required
 */
record CreateConsumingPartyRequest(String code, @NotNull @Valid NameI18nRequest nameI18n) {}
