package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.telemetry.CoreEventTelemetry;
import io.aria.conductor.execution.telemetry.RunTelemetryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F17/F9 wiring: the run coordinator is constructed with {@code new} by
 * {@code CoreRuntimeConfiguration}, so its telemetry collaborator arrives through
 * the setter's {@code @Autowired(required = false)}. That is an invisible
 * mechanism; if it stopped applying, live runs would silently keep the empty
 * tool-call audit the finding describes. This pins it against a real Spring
 * context, while a wiring without the bean (unit tests) keeps working.
 */
class CoreExecutionServiceTelemetryInjectionTest {

    @Test
    void aBeanWiredCoordinatorReceivesTheTelemetryAdapter() {
        CoreExecutionService coordinator = coordinator();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("coordinator", CoreExecutionService.class, () -> coordinator);
            context.registerBean("telemetry", CoreEventTelemetry.class,
                    () -> new CoreEventTelemetry(mock(RunTelemetryService.class)));
            context.refresh();

            assertThat(coordinator.telemetry()).isNotNull();
        }
    }

    @Test
    void aWiringWithoutTheTelemetryBeanStillCreatesTheCoordinator() {
        CoreExecutionService coordinator = coordinator();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("coordinator", CoreExecutionService.class, () -> coordinator);
            context.refresh();

            // An absent optional collaborator is skipped, never an unsatisfied-dependency failure.
            assertThat(coordinator.telemetry()).isNull();
        }
    }

    private static CoreExecutionService coordinator() {
        ExecutionBackend backend = mock(ExecutionBackend.class);
        when(backend.mode()).thenReturn(ExecutionMode.HOST);
        return new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)),
                mock(WorkspaceService.class), mock(RunFinalizer.class), new RunRuntimeRegistry(),
                mock(PermissionCoordinator.class), mock(CoreCredentialService.class),
                mock(ActorTokenService.class), mock(RunExecutionBindingRepository.class),
                new RunInputCoordinator(event -> { }), event -> { }, new TaskDeadlineProperties());
    }
}
