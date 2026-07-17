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

@SpringBootTest(classes = {ActApplication.class, NoopLlmTestConfig.class})
@ActiveProfiles({"h2", "noop-llm"})
class DevSqlControllerH2ProfilePresenceTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void controllerIsLoadedUnderH2Profile() {
        assertThat(applicationContext.getBean(DevSqlController.class)).isNotNull();
    }
}
