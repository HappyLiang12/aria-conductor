package io.aria.conductor.execution.engine;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArRenderSignalsTest {

    @Test
    void emitsTheRenderPayloadForTheRunsStream() {
        RunStreamRegistry registry = mock(RunStreamRegistry.class);
        UUID runId = UUID.randomUUID();
        when(registry.emit(eq(runId), eq(ArRenderSignals.EVENT_NAME), anyMap())).thenReturn(true);

        boolean delivered = new ArRenderSignals(registry).emit(runId, Map.of(
                "primitive", "track",
                "target", Map.of("trackId", 7),
                "text", "TV remote"));

        assertThat(delivered).isTrue();
        ArgumentCaptor<Map<String, Object>> captor = payloadCaptor();
        verify(registry).emit(eq(runId), eq("ar_render"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKeys("runId", "renderId", "render");
        assertThat(payload.get("runId")).isEqualTo(runId.toString());
        assertThat(payload.get("renderId")).isInstanceOf(String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> render = (Map<String, Object>) payload.get("render");
        assertThat(render).containsEntry("primitive", "track").containsEntry("text", "TV remote");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) render.get("target");
        assertThat(target).containsEntry("trackId", 7);
    }

    @Test
    void eachRenderCarriesItsOwnRenderId() {
        RunStreamRegistry registry = mock(RunStreamRegistry.class);
        UUID runId = UUID.randomUUID();
        when(registry.emit(eq(runId), eq(ArRenderSignals.EVENT_NAME), anyMap())).thenReturn(true);
        ArRenderSignals signals = new ArRenderSignals(registry);

        signals.emit(runId, Map.of("primitive", "track"));
        signals.emit(runId, Map.of("primitive", "clear"));

        ArgumentCaptor<Map<String, Object>> captor = payloadCaptor();
        verify(registry, org.mockito.Mockito.times(2)).emit(eq(runId), eq("ar_render"), captor.capture());
        List<Map<String, Object>> payloads = captor.getAllValues();
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).get("renderId")).isNotEqualTo(payloads.get(1).get("renderId"));
    }

    @Test
    void noLiveStreamIsReportedAsNotDelivered() {
        ArRenderSignals signals = new ArRenderSignals(new RunStreamRegistry());

        assertThat(signals.emit(UUID.randomUUID(), Map.of("primitive", "clear"))).isFalse();
    }

    @Test
    void aMissingRunOrRenderNeverReachesTheRegistry() {
        RunStreamRegistry registry = mock(RunStreamRegistry.class);
        ArRenderSignals signals = new ArRenderSignals(registry);

        assertThat(signals.emit(null, Map.of("primitive", "clear"))).isFalse();
        assertThat(signals.emit(UUID.randomUUID(), null)).isFalse();

        verifyNoInteractions(registry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
