package sy.khatm.platform.issuerclient.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** KH-2.8.1 (spec FS-2.7a D2) — the {@code khi_<prefix>_<secret>} key shape and its hashing. */
class ApiKeyGeneratorTest {

  private final ApiKeyGenerator generator =
      new ApiKeyGenerator(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

  @Test
  void generate_producesTheSpecifiedShape_andOnlyTheSecretIsHashed() {
    ApiKeyGenerator.Generated key = generator.generate();

    assertThat(key.rawKey()).startsWith("khi_").hasSize(4 + 10 + 1 + 43);
    assertThat(key.prefix()).matches("[a-z2-7]{10}");
    assertThat(key.rawKey()).isEqualTo("khi_" + key.prefix() + "_" + secretOf(key.rawKey()));
    assertThat(secretOf(key.rawKey())).matches("[A-Za-z0-9_-]{43}");

    ApiKeyGenerator.Parsed parsed = ApiKeyGenerator.parse(key.rawKey()).orElseThrow();
    assertThat(parsed.prefix()).isEqualTo(key.prefix());
    assertThat(generator.matches(parsed.secret(), key.secretHash())).isTrue();
    assertThat(generator.matches(parsed.secret() + "x", key.secretHash())).isFalse();
    assertThat(new String(key.secretHash()))
        .startsWith("$argon2id$")
        .doesNotContain(parsed.secret());
  }

  @Test
  void generate_isRandom() {
    Set<String> prefixes = new HashSet<>();
    Set<String> secrets = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      String raw = generator.generate().rawKey();
      prefixes.add(raw.substring(4, 14));
      secrets.add(secretOf(raw));
    }
    assertThat(prefixes).hasSize(20);
    assertThat(secrets).hasSize(20);
  }

  @Test
  void parse_rejectsAnythingNotExactlyTheGeneratedShape_beforeAnyHashing() {
    String good = generator.generate().rawKey();
    assertThat(ApiKeyGenerator.parse(good)).isPresent();
    for (String bad :
        new String[] {
          null,
          "",
          "khi_",
          "khk_" + good.substring(4),
          good.substring(0, good.length() - 1),
          good + "x",
          good.replace('_', '-'),
          "khi_" + good.substring(4, 14).toUpperCase() + good.substring(14),
          "khi_" + "1".repeat(10) + good.substring(14),
          good.substring(0, 20) + "*" + good.substring(21)
        }) {
      Optional<ApiKeyGenerator.Parsed> parsed = ApiKeyGenerator.parse(bad);
      assertThat(parsed).as("input: %s", bad).isEmpty();
    }
  }

  private static String secretOf(String rawKey) {
    return rawKey.substring(4 + 10 + 1);
  }
}
