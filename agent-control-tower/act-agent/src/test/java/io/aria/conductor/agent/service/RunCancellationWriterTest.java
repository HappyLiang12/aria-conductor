package io.aria.conductor.agent.service;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one terminal CANCELLED write for a run (#111 fix round 1): the atomic
 * conditional update decides who owns the terminal transition, and the
 * completion event is published only by the writer that won it. A losing or
 * refused write publishes nothing.
 */
@ExtendWith(MockitoExtension.class)
class RunCancellationWriterTest {

    @Mock RunRepository runRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks RunCancellationWriter writer;

    @Test
    void cancel_whenTheConditionalUpdateWins_persistsOnceAndPublishesExactlyOneEvent() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Run cancelled = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.CANCELLED).build();
        when(runRepository.cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenReturn(1);
        when(runRepository.findById(id)).thenReturn(Optional.of(cancelled));

        Run result = writer.cancel(id);

        assertThat(result).isSameAs(cancelled);

        ArgumentCaptor<Instant> completedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> updatedAt = ArgumentCaptor.forClass(Instant.class);
        verify(runRepository).cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                completedAt.capture(), updatedAt.capture(), eq(RunService.cancelableStatuses()));
        assertThat(completedAt.getValue()).isNotNull();
        assertThat(updatedAt.getValue()).isEqualTo(completedAt.getValue());

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getRunId()).isEqualTo(id);
        assertThat(event.getValue().getAgentId()).isEqualTo(agentId);
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);

        // The conditional update is the only terminal write; the old read-then-save
        // shape (which could overwrite a completed row) is gone.
        verify(runRepository, never()).save(any(Run.class));
    }

    @Test
    void cancel_whenTheCommittedRunIsTerminal_publishesNothingAndRefusesTheTransition() {
        UUID id = UUID.randomUUID();
        when(runRepository.cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenReturn(0);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.COMPLETED)));

        assertThatThrownBy(() -> writer.cancel(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Invalid state transition for Run: COMPLETED -> CANCELLED");

        verify(eventPublisher, never()).publishEvent(any());
        verify(runRepository, never()).findById(any());
        verify(runRepository, never()).save(any(Run.class));
    }

    @Test
    void cancel_whenTheRunDoesNotExist_refusesWithNotFound() {
        UUID id = UUID.randomUUID();
        when(runRepository.cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenReturn(0);
        when(runRepository.findStateById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.cancel(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Run not found with id: " + id);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancel_isOneShortTransaction_soTheVerifiedStopNeverRunsInsideIt() throws Exception {
        Transactional annotation = RunCancellationWriter.class
                .getMethod("cancel", UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    private static RunRepository.RunStateView stateView(RunStatus status) {
        return new RunRepository.RunStateView() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return null;
            }
        };
    }
}
