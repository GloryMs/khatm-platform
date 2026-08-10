package sy.khatm.platform.credential.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import sy.khatm.platform.credential.api.AttestationRequest;
import sy.khatm.platform.credential.api.IssueRequest;
import sy.khatm.platform.credential.api.IssueResponse;
import sy.khatm.platform.credential.domain.CredentialService;
import sy.khatm.platform.schema.api.SchemaCatalog;
import sy.khatm.platform.schema.api.SchemaDefinition;
import sy.khatm.platform.shared.LocalizedText;

/**
 * Seeds the {@code AttestedDocument/v1} demo schema ({@code requires_attestation=true}) and one
 * demo credential issued against it with a real {@code attestation} object (KH-2.4, spec FS-2.4
 * item 4) — the non-automated issuer-portal flow's worked example, alongside {@link DemoSeeder}'s
 * unattested one.
 *
 * <p>{@code doc_sha256} carries a real format constraint ({@code ^[0-9a-f]{64}$}, spec FS-2.4 item
 * 3, a genuine SHA-256 hex digest here, not a placeholder) — a regression in pattern enforcement
 * would fail this seeder's own issuance, not just a test. Every claim field is listed in {@code
 * sdFields} (withholdable at presentation time — spec FS-0.4 D2; this is independent of D1's "every
 * claim always becomes a disclosure," which still holds for all four).
 *
 * <p>Active only in {@code local}/{@code dev} Spring profiles, same as {@link DemoSeeder}.
 */
@Component
@Profile({"local", "dev"})
@Order(1)
class AttestedDocumentSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(AttestedDocumentSeeder.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> SD_FIELDS =
      List.of("doc_sha256", "doc_type", "original_issue_date", "attestation_note");

  private final SchemaCatalog schemas;
  private final CredentialService credentials;

  AttestedDocumentSeeder(SchemaCatalog schemas, CredentialService credentials) {
    this.schemas = schemas;
    this.credentials = credentials;
  }

  @Override
  public void run(String... args) {
    try {
      schemas.ensurePublished(attestedDocumentSchema());

      Map<String, Object> claims =
          Map.of(
              "doc_sha256", "0e096349c319f2e7560d15100f23541ac79abf1a85ed46730c5e91966b7924ae",
              "doc_type", "IDENTITY_DOCUMENT",
              "original_issue_date", "2020-01-15",
              "attestation_note", "Scanned original compared against the physical document.");

      IssueResponse issued =
          credentials.issue(
              new IssueRequest(
                  "AttestedDocument/v1",
                  "holder-demo-attested-001",
                  1,
                  60,
                  claims,
                  SD_FIELDS,
                  new AttestationRequest(
                      "Attested by the demo seeder — scanned original verified.")));
      log.info("========================================================");
      log.info("  Khatm platform ready. Seeded demo attested-document credential:");
      log.info("   id  = {}", issued.id());
      log.info("   ref = {}", issued.ref());
      log.info("========================================================");
    } catch (Exception e) {
      log.warn("Attested-document demo seeder skipped: {}", e.getMessage());
    }
  }

  private static SchemaDefinition attestedDocumentSchema() {
    ObjectNode claimsDef = JSON.createObjectNode();
    field(
        claimsDef,
        "doc_sha256",
        "text",
        "^[0-9a-f]{64}$",
        "Document SHA-256",
        "بصمة SHA-256 للوثيقة");
    field(claimsDef, "doc_type", "text", null, "Document Type", "نوع الوثيقة");
    field(
        claimsDef,
        "original_issue_date",
        "date",
        null,
        "Original Issue Date",
        "تاريخ الإصدار الأصلي");
    field(claimsDef, "attestation_note", "text", null, "Attestation Note", "ملاحظة التصديق");

    return new SchemaDefinition(
        "AttestedDocument/v1",
        1,
        new LocalizedText("Attested Document", "وثيقة مصدَّقة"),
        claimsDef.toString(),
        SD_FIELDS,
        1,
        true);
  }

  private static void field(
      ObjectNode claimsDef,
      String name,
      String type,
      String pattern,
      String labelEn,
      String labelAr) {
    ObjectNode fieldNode = claimsDef.putObject(name);
    fieldNode.put("type", type);
    fieldNode.put("required", false);
    if (pattern != null) {
      fieldNode.put("pattern", pattern);
    }
    ObjectNode label = fieldNode.putObject("label_i18n");
    label.put("en", labelEn);
    label.put("ar", labelAr);
  }
}
