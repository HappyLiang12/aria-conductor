package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskDeadlinePropertiesTest {

    @Mock
    SystemConfigService systemConfigService;

    @Test
    void deadlineReadsTheConfiguredMinutes() {
        when(systemConfigService.getInt("aria.tasks.deadline-minutes",
                TaskDeadlineProperties.DEFAULT_DEADLINE_MINUTES, 1, 1440)).thenReturn(90);
        TaskDeadlineProperties properties = new TaskDeadlineProperties(systemConfigService);

        assertThat(properties.deadline()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void deadlineFallsBackToTheBoundDefaultWhenSystemConfigFails() {
        when(systemConfigService.getInt("aria.tasks.deadline-minutes",
                TaskDeadlineProperties.DEFAULT_DEADLINE_MINUTES, 1, 1440))
                .thenThrow(new RuntimeException("database unavailable"));
        TaskDeadlineProperties properties = new TaskDeadlineProperties(systemConfigService);

        assertThat(properties.deadline()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void withoutSystemConfigTheBoundPropertyIsTheFallback() {
        TaskDeadlineProperties properties = new TaskDeadlineProperties();
        assertThat(properties.deadline()).isEqualTo(Duration.ofMinutes(45));

        properties.setDeadlineMinutes(30);
        assertThat(properties.deadline()).isEqualTo(Duration.ofMinutes(30));
    }
}
