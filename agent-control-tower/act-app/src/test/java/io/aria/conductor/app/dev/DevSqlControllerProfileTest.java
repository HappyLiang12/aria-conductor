package io.aria.conductor.app.dev;

import io.aria.conductor.ActApplication;
import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.app.NoopLlmTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevSqlControllerProfileTest extends BaseH2IntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void controllerIsNotLoadedUnderTestProfile() {
        assertThatThrownBy(() -> applicationContext.getBean(DevSqlController.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}

@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        // The h2 profile's datasource is the shared file ./data/act_db (application-h2.yml:14),
        // and this test asserts profile-conditional wiring (DevSqlController.java:11), not the
        // file datasource. Left un-overridden it needs that file exclusively: it fails with
        // "The file is locked" whenever a local stack runs (run2 regression-mvn-test) and
        // applies Flyway migrations to the developer's database. H2ProfileConfigurationTest
        // keeps asserting the file URL itself, without opening a connection.
        properties = "spring.datasource.url=jdbc:h2:mem:dev_sql_h2_presence;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles({"h2", "noop-llm"})
class DevSqlControllerH2ProfilePresenceTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void controllerIsLoadedUnderH2Profile() {
        assertThat(applicationContext.getBean(DevSqlController.class)).isNotNull();
    }
}
