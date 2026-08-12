package sy.khatm.platform.credential.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Confirms Jackson deserializes {@code schemaId} from the JSON request body via {@link
 * IssueRequest}'s canonical (8-arg) constructor, not the secondary 7-arg convenience overload added
 * for existing {@code schemaCode}-only Java call sites — a plain unit test, no Spring context,
 * isolating the deserialization layer specifically.
 */
class IssueRequestJsonTest {

  @Test
  void deserializesSchemaIdFromJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    UUID schemaId = UUID.randomUUID();
    String json =
        """
        {
          "schemaCode": "BaCertificate",
          "holderRef": "holder-1",
          "claims": {"test_field": "123456789"},
          "sdFields": [],
          "schemaId": "%s"
        }
        """
            .formatted(schemaId);

    IssueRequest req = mapper.readValue(json, IssueRequest.class);

    assertThat(req.schemaId()).isEqualTo(schemaId);
    assertThat(req.holderRef()).isEqualTo("holder-1");
  }
}
