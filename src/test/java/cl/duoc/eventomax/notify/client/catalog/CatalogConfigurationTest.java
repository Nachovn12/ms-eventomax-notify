package cl.duoc.eventomax.notify.client.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogConfigurationTest {
    private ApplicationContextRunner runner(Map<String, Object> env) {
        return new ApplicationContextRunner().withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove("systemEnvironment");
            sources.remove("systemProperties");
            sources.addFirst(new SystemEnvironmentPropertySource("fixtureEnvironment", env));
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                        .forEach(sources::addLast);
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        }).withUserConfiguration(ClientConfig.class);
    }

    @Test
    void canonicalEnvironmentVariableWinsOverLegacyAlias() {
        assertUrl(Map.of("CATALOG_BASE_URL", "http://catalog-current:8080/",
                "CATALOG_SERVICE_URL", "http://catalog-legacy:8080"), "http://catalog-current:8080");
    }

    @Test
    void legacyAliasRemainsSupported() {
        assertUrl(Map.of("CATALOG_SERVICE_URL", "http://catalog-legacy:8080"), "http://catalog-legacy:8080");
    }

    @Test
    void explicitSpringPropertyWinsOverAliases() {
        assertUrl(Map.of("EVENTOMAX_NOTIFY_CATALOG_BASE_URL", "http://catalog-property:8080",
                "CATALOG_BASE_URL", "http://catalog-current:8080"), "http://catalog-property:8080");
    }

    @Test
    void developmentDefaultIsPreserved() {
        assertUrl(Map.of(), "http://localhost:8081");
    }

    private void assertUrl(Map<String, Object> env, String expected) {
        runner(env).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(RestCatalogReservationClient.class), "baseUrl"))
                    .isEqualTo(expected);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RestCatalogReservationClient.class)
    static class ClientConfig {}
}
