package sy.khatm.platform.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.UncheckedIOException;

/**
 * The single shared JPA converter for {@code name_i18n} / {@code label_i18n} JSONB columns
 * (CONVENTIONS.md §3) — entities must reuse this converter rather than re-implementing JSON mapping
 * per field.
 *
 * <p>Usage on an entity field:
 *
 * <pre>{@code
 * @Convert(converter = LocalizedTextConverter.class)
 * @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
 * @ColumnTransformer(write = "?::jsonb")
 * private LocalizedText nameI18n;
 * }</pre>
 *
 * <p>The {@code @ColumnTransformer(write = "?::jsonb")} cast is required because the PostgreSQL
 * JDBC driver otherwise binds the converted string as {@code varchar}, which Postgres rejects for a
 * {@code jsonb} column under server-side prepared statements.
 */
@Converter
public class LocalizedTextConverter implements AttributeConverter<LocalizedText, String> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(LocalizedText attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("Failed to serialize LocalizedText to JSON", e);
    }
  }

  @Override
  public LocalizedText convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    try {
      return MAPPER.readValue(dbData, LocalizedText.class);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("Failed to deserialize LocalizedText from JSON", e);
    }
  }
}
