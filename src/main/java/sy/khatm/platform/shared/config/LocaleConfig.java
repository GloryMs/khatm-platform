package sy.khatm.platform.shared.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Locale resolution and message-bundle configuration (CLAUDE.md work rule 2, spec FS-0.6a D5).
 *
 * <p>Locale comes exclusively from the {@code Accept-Language} header — {@code en} by default,
 * {@code en}/{@code ar} supported. Any other requested language falls back to {@code en} silently
 * (no error for an unsupported language, spec D5): {@link AcceptHeaderLocaleResolver}'s built-in
 * {@code Locale.lookup} behavior against {@link #localeResolver}'s configured supported-locales
 * list already does exactly this — no custom fallback logic needed.
 */
@Configuration
class LocaleConfig {

  /**
   * Resolve locale from {@code Accept-Language} only — never a session/cookie (D5: a stored console
   * preference is that repo's concern, not the platform's).
   *
   * @return the locale resolver bean
   */
  @Bean
  LocaleResolver localeResolver() {
    AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
    resolver.setDefaultLocale(Locale.ENGLISH);
    resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.of("ar")));
    return resolver;
  }

  /**
   * Explicit UTF-8: {@code .properties} files default to ISO-8859-1, which would silently corrupt
   * the Arabic bundle (spec FS-0.6a §4).
   *
   * @return the message source bean
   */
  @Bean
  MessageSource messageSource() {
    ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
    source.setBasenames("classpath:i18n/messages");
    source.setDefaultEncoding(StandardCharsets.UTF_8.name());
    source.setFallbackToSystemLocale(false);
    return source;
  }
}
