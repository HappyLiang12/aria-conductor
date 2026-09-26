package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Task 19 fix round 7: the run store's runtime-control port. A coordinator-owned
 * run's pause/resume is verified through the coordinator and only a verified
 * ack passes; a refusal carries the exact pending-control reason and claims no
 * state. A run the coordinator does not own (legacy, finalized) is not owned by
 * the port, so the caller keeps its recorded transition.
 */
@ExtendWith(MockitoExtension.class)
class CoordinatedRunRuntimeControlTest {

    @Mock ObjectProvider<CoreExecutionService> coordinatorProvider;
    @Mock CoreExecutionService coordinator;

    private final UUID agentId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void owns_isTheCoordinatorsByRunView() {
        CoordinatedRunRuntimeControl control = new CoordinatedRunRuntimeControl(coordinatorProvider);
        when(coordinatorProvider.getIfAvailable()).thenReturn(coordinator);
        when(coordinator.activeRuns(agentId)).thenReturn(Set.of(runId), Set.of());

        assertThat(control.owns(agentId, runId)).isTrue();
        assertThat(control.owns(agentId, runId)).isFalse();
        assertThat(control.owns(null, runId)).isFalse();
    }

    @Test
    void owns_isFalseWithoutADeployedCoordinator() {
        CoordinatedRunRuntimeControl control = new CoordinatedRunRuntimeControl(coordinatorProvider);

        assertThat(control.owns(agentId, runId)).isFalse();
    }

    @Test
    void pause_passesOnAVerifiedPausedAck() {
        CoordinatedRunRuntimeControl control = new CoordinatedRunRuntimeControl(coordinatorProvider);
        when(coordinatorProvider.getIfAvailable()).thenReturn(coordinator);
        when(coordinator.pause(runId)).thenReturn(
                CompletableFuture.completedFuture(new ControlAck(ControlState.PAUSED, true)));

        control.pause(runId);
    }

    @Test
    void pause_refusalSurfacesTheExactPendingControlReason() {
        CoordinatedRunRuntimeControl control = new CoordinatedRunRuntimeControl(coordinatorProvider);
        when(coordinatorProvider.getIfAvailable()).thenReturn(coordinator);
        when(coordinator.pause(runId)).thenReturn(
                CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, false)));
        when(coordinator.pendingControl(runId)).thenReturn(Optional.of(new RunRuntimeRegistry.ControlRequest(
                ControlState.PAUSED, Instant.now(),
                "Pause refused for run " + runId + ": the core reported RUNNING (verified=false)")));

        assertThatThrownBy(() -> control.pause(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not paused")
                .hasMessageContaining("the core reported RUNNING (verified=false)");
    }

    @Test
    void resume_refusalSurfacesTheFallbackReasonWithoutAPendingRecord() {
        CoordinatedRunRuntimeControl control = new CoordinatedRunRuntimeControl(coordinatorProvider);
        when(coordinatorProvider.getIfAvailable()).thenReturn(coordinator);
        when(coordinator.resume(runId)).thenReturn(
                CompletableFuture.completedFuture(new ControlAck(ControlState.PAUSED, false)));
        when(coordinator.pendingControl(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> control.resume(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not resumed")
                .hasMessageContaining("did not verify the resume");
    }

    @Test
    void resume_passesOnAVerifiedRunningAck() {
        CoordinatedRunRuntimeControl control = new CoordinatedRunRuntimeControl(coordinatorProvider);
        when(coordinatorProvider.getIfAvailable()).thenReturn(coordinator);
        when(coordinator.resume(runId)).thenReturn(
                CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, true)));

        control.resume(runId);
    }
}
