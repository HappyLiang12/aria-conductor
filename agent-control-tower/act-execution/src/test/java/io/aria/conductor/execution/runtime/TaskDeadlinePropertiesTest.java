package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.common.config.SystemConfigKeys;
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
        when(systemConfigService.getInt(SystemConfigKeys.TASK_DEADLINE_MINUTES,
                SystemConfigKeys.TASK_DEADLINE_DEFAULT_MINUTES,
                SystemConfigKeys.TASK_DEADLINE_MIN_MINUTES,
                SystemConfigKeys.TASK_DEADLINE_MAX_MINUTES)).thenReturn(90);
        TaskDeadlineProperties properties = new TaskDeadlineProperties(systemConfigService);

        assertThat(properties.deadline()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void deadlineFallsBackToTheBoundDefaultWhenSystemConfigFails() {
        when(systemConfigService.getInt(SystemConfigKeys.TASK_DEADLINE_MINUTES,
                SystemConfigKeys.TASK_DEADLINE_DEFAULT_MINUTES,
                SystemConfigKeys.TASK_DEADLINE_MIN_MINUTES,
                SystemConfigKeys.TASK_DEADLINE_MAX_MINUTES))
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
