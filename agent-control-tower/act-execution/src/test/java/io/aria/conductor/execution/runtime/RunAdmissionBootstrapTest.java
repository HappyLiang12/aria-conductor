package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunAdmissionBootstrapTest {

    @Test
    void reStartsEveryPendingRunInCreationOrder() {
        RunRepository runs = mock(RunRepository.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        Run older = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:00:00Z")).build();
        Run newer = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:01:00Z")).build();
        when(runs.findByStatus(RunStatus.PENDING)).thenReturn(List.of(newer, older));

        new RunAdmissionBootstrap(runs, engine).onApplicationReady();

        var order = inOrder(engine);
        order.verify(engine).startRun(older.getId());
        order.verify(engine).startRun(newer.getId());
    }

    @Test
    void aFailingRunDoesNotStopTheRest() {
        RunRepository runs = mock(RunRepository.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        Run first = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:00:00Z")).build();
        Run second = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:01:00Z")).build();
        when(runs.findByStatus(RunStatus.PENDING)).thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(engine).startRun(first.getId());

        new RunAdmissionBootstrap(runs, engine).onApplicationReady();

        verify(engine).startRun(second.getId());
    }
}
