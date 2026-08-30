package io.aria.conductor.app;

import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring guard (review C1): the production OpenCodeAdkProvider must receive a
 * real ApplicationEventPublisher, otherwise the progress pump silently drops
 * every run.progress event (the 5-arg constructor's null-publisher path is
 * test-only).
 */
class OpenCodeProviderWiringTest extends BaseH2IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void productionProviderReceivesEventPublisher() throws Exception {
        OpenCodeAdkProvider provider = context.getBean(OpenCodeAdkProvider.class);

        Field f = OpenCodeAdkProvider.class.getDeclaredField("eventPublisher");
        f.setAccessible(true);
        assertThat(f.get(provider)).as("eventPublisher must be injected in production").isNotNull();
    }
}
