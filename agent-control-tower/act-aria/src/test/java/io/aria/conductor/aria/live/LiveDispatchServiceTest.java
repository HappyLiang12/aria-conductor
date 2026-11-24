package io.aria.conductor.aria.live;

import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-socket dispatch registry (Phase 4): a child run belongs to the socket that
 * approved it, leaves on its terminal status, and survives another socket's cleanup.
 */
class LiveDispatchServiceTest {

    private final RunService runs = mock(RunService.class);
    private final LiveFrames frames = mock(LiveFrames.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final LiveSession owner = new LiveSession("s1", "conv", null, null);

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    private LiveDispatchService withTimers(long reminderMs, long timeoutMs) {
        LiveProperties properties = new LiveProperties(false, null, null, "", 0, 0, false,
                null, null, 0, reminderMs, timeoutMs, 500);
        return new LiveDispatchService(runs, frames, properties, scheduler);
    }

    /** A run still active at the timer's first check. */
    private void running(UUID run, RunStatus status) {
        when(runs.getRun(run)).thenReturn(RunResponse.builder().id(run).status(status).build());
    }

    @Test
    void aLongRunAnnouncesItselfOnce() throws Exception {
        LiveDispatchService service = withTimers(40, 5_000);
        UUID run = UUID.randomUUID();
        running(run, RunStatus.RUNNING);
        service.register(run, owner, "Analyst");

        Thread.sleep(150);

        ArgumentCaptor<Map<String, Object>> tower = mapCaptor();
        verify(frames).toBridge(eq(owner), tower.capture());
        assertThat(tower.getValue().get("text")).isEqualTo("[tower] Analyst 仲做緊");
        assertThat(tower.getValue().get("mode")).isEqualTo("idle");
        service.remove(run);
    }

    @Test
    void theTimeoutStopsTheRunAndReportsItExactlyOnce() throws Exception {
        LiveDispatchService service = withTimers(5_000, 40);
        UUID run = UUID.randomUUID();
        running(run, RunStatus.RUNNING);
        service.register(run, owner, "Analyst");

        Thread.sleep(200);

        verify(runs).cancelRun(run);
        ArgumentCaptor<Map<String, Object>> frames_ = mapCaptor();
        verify(frames, org.mockito.Mockito.times(1)).toPhone(eq(owner), frames_.capture());
        assertThat(frames_.getValue().get("state")).isEqualTo("failed");
        assertThat(frames_.getValue().get("summary")).isEqualTo("failed: timeout");
        assertThat(frames_.getValue().get("origin")).isEqualTo("dispatch");
        ArgumentCaptor<Map<String, Object>> tower = mapCaptor();
        verify(frames).toBridge(eq(owner), tower.capture());
        assertThat(tower.getValue().get("mode")).isEqualTo("interrupt");
        assertThat(service.find(run)).isEmpty();
    }

    @Test
    void aFinishedRunIsNotReminded() throws Exception {
        LiveDispatchService service = withTimers(30, 5_000);
        UUID run = UUID.randomUUID();
        running(run, RunStatus.COMPLETED);
        service.register(run, owner, "Analyst");

        Thread.sleep(120);

        verify(frames, never()).toBridge(any(), any());
        service.remove(run);
    }

    @Test
    void closingTheSocketCancelsBothTimers() throws Exception {
        LiveDispatchService service = withTimers(20, 40);
        UUID run = UUID.randomUUID();
        running(run, RunStatus.RUNNING);
        service.register(run, owner, "Analyst");

        service.removeSocket(owner);
        Thread.sleep(120);

        verify(frames, never()).toBridge(any(), any());
        verify(frames, never()).toPhone(any(), any());
        verify(runs, never()).cancelRun(any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Test
    void aRunBelongsToOneSocketAndIsRemovedOnce() {
        LiveDispatchService service = new LiveDispatchService();
        LiveSession one = new LiveSession("s1", null, null, null);
        LiveSession two = new LiveSession("s2", null, null, null);
        UUID run = UUID.randomUUID();

        service.register(run, one, "Analyst");
        assertThat(service.find(run)).get()
                .extracting(LiveDispatchService.Dispatch::agentName).isEqualTo("Analyst");
        assertThat(service.remove(run)).isPresent();
        assertThat(service.remove(run)).isEmpty();

        service.register(run, two, "Analyst");
        service.removeSocket(one);
        assertThat(service.find(run)).as("another socket's cleanup leaves it").isPresent();
        service.removeSocket(two);
        assertThat(service.find(run)).isEmpty();
        assertThat(service.runIds()).isEmpty();
    }

    @Test
    void aMissingAgentNameIsAnEmptyStringNeverNull() {
        LiveDispatchService service = new LiveDispatchService();
        UUID run = UUID.randomUUID();
        service.register(run, new LiveSession("s1", null, null, null), null);
        assertThat(service.find(run)).get()
                .extracting(LiveDispatchService.Dispatch::agentName).isEqualTo("");
    }
}
