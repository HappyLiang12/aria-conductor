package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(
        classes = H2ProfileConfigurationTest.EmptyConfig.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ActiveProfiles("h2")
class H2ProfileConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    @Autowired
    private Environment environment;

    @Test
    void shouldUsePersistentH2WithFlywayEnabled() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .startsWith("jdbc:h2:file:./data/act_db");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.h2.Driver");
    }
}
