package sy.khatm.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * chore/public-base-url — {@link PublicUrlBuilder} builds every absolute self-URL purely from the
 * configured {@code khatm.public-base-url}, never from a request. Constructing it directly with an
 * arbitrary, non-localhost base URL and no {@code HttpServletRequest} in scope at all is itself
 * proof there is no request-derived code path left to regress into.
 */
class PublicUrlBuilderTest {

  @Test
  void build_usesConfiguredBaseUrl_regardlessOfLeadingSlashOnPath() {
    PublicUrlBuilder builder = builderWith("https://khatm.example.org");

    assertThat(builder.build("/sl/khatm-default/list-1"))
        .isEqualTo("https://khatm.example.org/sl/khatm-default/list-1");
    assertThat(builder.build("sl/khatm-default/list-1"))
        .isEqualTo("https://khatm.example.org/sl/khatm-default/list-1");
  }

  @Test
  void build_stripsTrailingSlashFromConfiguredBaseUrl() {
    PublicUrlBuilder builder = builderWith("https://khatm.example.org/");

    assertThat(builder.build("/x")).isEqualTo("https://khatm.example.org/x");
  }

  @Test
  void constructor_blankBaseUrl_outsideLocalProfile_throwsAndNamesTheProperty() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("test");

    assertThatThrownBy(() -> new PublicUrlBuilder(new PublicUrlProperties(""), env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("khatm.public-base-url");
  }

  @Test
  void constructor_missingBaseUrl_onLocalProfile_doesNotThrow() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local");

    assertThatCode(() -> new PublicUrlBuilder(new PublicUrlProperties(null), env))
        .doesNotThrowAnyException();
  }

  private static PublicUrlBuilder builderWith(String baseUrl) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("test");
    return new PublicUrlBuilder(new PublicUrlProperties(baseUrl), env);
  }
}
