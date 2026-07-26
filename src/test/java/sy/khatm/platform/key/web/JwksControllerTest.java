package sy.khatm.platform.key.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.key.domain.KeyLifecycleService;
import sy.khatm.platform.key.domain.PublishedKey;

/**
 * FS-0.5 §6 / §8 DoD #6 (HTTP-response half): the JWKS endpoint serves only public JWK material,
 * with the expected {@code Cache-Control} header, sourced from {@link KeyLifecycleService}'s
 * publishable keys.
 *
 * <p>A plain Mockito unit test — no Spring context or database needed, since {@link JwksController}
 * has exactly one collaborator.
 */
@ExtendWith(MockitoExtension.class)
class JwksControllerTest {

  private static final String ACTIVE_JWK =
      "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"khatm-default:key-1\","
          + "\"x\":\"AAAA\",\"y\":\"BBBB\"}";
  private static final String RETIRING_JWK =
      "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"khatm-default:key-0\","
          + "\"x\":\"CCCC\",\"y\":\"DDDD\"}";

  @Mock private KeyLifecycleService lifecycle;

  private JwksController controller;

  @BeforeEach
  void setUp() {
    controller = new JwksController(lifecycle, new ObjectMapper());
  }

  @Test
  void jwks_returnsPublishableKeysOnly_withNoPrivateMaterial_andCacheHeader() {
    when(lifecycle.publishableKeysForDefaultTenantJwks(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new PublishedKey("khatm-default:key-1", ACTIVE_JWK),
                new PublishedKey("khatm-default:key-0", RETIRING_JWK)));

    ResponseEntity<String> response = controller.jwks();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getCacheControl()).contains("max-age=300");
    String body = response.getBody();
    assertThat(body).contains("khatm-default:key-1").contains("khatm-default:key-0");
    // The private EC component (RFC 7518 §6.2.2.1) must never appear in the response.
    assertThat(body).doesNotContain("\"d\"");
  }

  @Test
  void jwks_noPublishableKeys_returnsEmptyKeysArray() {
    when(lifecycle.publishableKeysForDefaultTenantJwks(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());

    ResponseEntity<String> response = controller.jwks();

    assertThat(response.getBody()).contains("\"keys\":[]");
  }
}
