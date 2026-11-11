package io.aria.conductor.execution.engine;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The run-owned emitter registry (spec §5.1 / decision A2): a run's stream is
 * addressed by run id so an ask arriving on the bridge reader thread can be
 * handed to the turn that caused it, an emitter that completed, errored, timed
 * out or was replaced can never receive another event, and no call here ever
 * throws into the engine loop.
 */
class RunStreamRegistryTest {

    /**
     * A real {@link SseEmitter} that records the frames it was asked to send. The
     * servlet container is absent here, so the frame is inspected as it is built.
     */
    static final class RecordingEmitter extends SseEmitter {
        final List<SseEventBuilder> frames = new CopyOnWriteArrayList<>();

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            frames.add(builder);
        }
    }

    /** The text parts of a frame: SSE writes the event name as an {@code event:} line. */
    private static String rendered(SseEmitter.SseEventBuilder frame) {
        StringBuilder text = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : frame.build()) {
            if (part.getMediaType() == null || MediaType.TEXT_PLAIN.includes(part.getMediaType())) {
                text.append(part.getData());
            }
        }
        return text.toString();
    }

    /** The JSON data part of a frame: the payload object and the media type it was handed over with. */
    private static ResponseBodyEmitter.DataWithMediaType jsonPart(SseEmitter.SseEventBuilder frame) {
        return frame.build().stream()
                .filter(part -> part.getMediaType() != null
                        && MediaType.APPLICATION_JSON.includes(part.getMediaType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the frame carries no application/json data part"));
    }

    @Test
    void emitReturnsFalseForAnUnregisteredRunAndNeverThrows() {
        RunStreamRegistry registry = new RunStreamRegistry();
        assertThat(registry.emit(UUID.randomUUID(), "approval_required", Map.of())).isFalse();
    }

    @Test
    void aNullRunIdIsNeverASignalTarget() {
        // ConcurrentHashMap rejects a null key, so an unguarded emit would throw into
        // the engine loop / the bridge reader thread it is called from.
        RunStreamRegistry registry = new RunStreamRegistry();
        assertThat(registry.emit(null, "approval_required", Map.of())).isFalse();
        assertThat(registry.hasStream(null)).isFalse();
    }

    @Test
    void aRegisteredRunReceivesTheEventUnderItsExactNameAndJsonPayload() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(runId, emitter);

        boolean emitted = registry.emit(runId, "approval_required", Map.of("approvalId", "approval-1"));

        assertThat(emitted).as("a live stream accepted the event").isTrue();
        assertThat(emitter.frames).hasSize(1);
        assertThat(rendered(emitter.frames.get(0)))
                .as("the exact event name Task 6 parses")
                .startsWith("event:approval_required\n");
        Object payload = jsonPart(emitter.frames.get(0)).getData();
        assertThat(payload).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload;
        assertThat(data).containsEntry("approvalId", "approval-1");
    }

    @Test
    void aCompletedEmitterIsUnregisteredAndEmitStops() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        registry.register(runId, emitter);
        emitter.complete();
        assertThat(registry.emit(runId, "approval_required", Map.of())).isFalse();
    }

    @Test
    void theCompletionCallbackTheContainerFiresUnbindsTheStream() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register(runId, emitter);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(completion.capture());

        // The servlet container invokes the registered callback when the async request ends.
        completion.getValue().run();

        assertThat(registry.hasStream(runId)).isFalse();
        assertThat(registry.emit(runId, "waiting_input", Map.of())).isFalse();
    }

    @Test
    void theErrorCallbackTheContainerFiresUnbindsTheStream() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register(runId, emitter);
        ArgumentCaptor<Consumer<Throwable>> error = consumerCaptor();
        verify(emitter).onError(error.capture());

        error.getValue().accept(new IOException("client went away"));

        assertThat(registry.hasStream(runId)).isFalse();
        assertThat(registry.emit(runId, "waiting_input", Map.of())).isFalse();
    }

    @Test
    void theTimeoutCallbackTheContainerFiresUnbindsTheStream() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register(runId, emitter);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(timeout.capture());

        timeout.getValue().run();

        assertThat(registry.hasStream(runId)).isFalse();
        assertThat(registry.emit(runId, "waiting_input", Map.of())).isFalse();
    }

    @Test
    void unregisterRemovesTheStreamImmediately() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        registry.register(runId, emitter);

        registry.unregister(runId);

        assertThat(registry.hasStream(runId)).isFalse();
        assertThat(registry.emit(runId, "approval_required", Map.of())).isFalse();
    }

    @Test
    void aFailingSendDropsTheStreamAndNeverThrows() throws Exception {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        registry.register(runId, broken);

        assertThat(registry.emit(runId, "approval_required", Map.of())).isFalse();
        assertThat(registry.hasStream(runId)).as("a failed write is not retried forever").isFalse();
    }

    @Test
    void aLateCompletionOfAReplacedEmitterDoesNotUnregisterTheNewStream() {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter previous = mock(SseEmitter.class);
        registry.register(runId, previous);
        ArgumentCaptor<Runnable> previousCompletion = ArgumentCaptor.forClass(Runnable.class);
        verify(previous).onCompletion(previousCompletion.capture());
        RecordingEmitter current = new RecordingEmitter();
        registry.register(runId, current);

        // The previous stream's transport finishes after the run's new stream registered.
        previousCompletion.getValue().run();

        assertThat(registry.emit(runId, "waiting_input", Map.of())).isTrue();
        assertThat(current.frames).hasSize(1);
    }

    @Test
    void emitNeverResurrectsACompletedEmitterUnderContention() throws Exception {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        registry.register(runId, emitter);
        emitter.complete();

        List<Boolean> emitted = emitConcurrently(registry, runId, 8, 200);

        assertThat(emitted).as("a completed stream is never handed another event")
                .hasSize(1600).containsOnly(false);
        assertThat(registry.hasStream(runId)).isFalse();
    }

    @Test
    void concurrentEmitsAndACompletionNeverThrowAndLeaveNoStream() throws Exception {
        RunStreamRegistry registry = new RunStreamRegistry();
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        registry.register(runId, emitter);

        ExecutorService pool = Executors.newFixedThreadPool(9);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Boolean>>> writers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                writers.add(pool.submit(() -> {
                    await(start);
                    List<Boolean> results = new ArrayList<>();
                    for (int n = 0; n < 250; n++) {
                        results.add(registry.emit(runId, "approval_required", Map.of("n", n)));
                    }
                    return results;
                }));
            }
            Future<Void> completer = pool.submit(() -> {
                await(start);
                emitter.complete();
                return null;
            });
            start.countDown();

            int accepted = 0;
            for (Future<List<Boolean>> writer : writers) {
                for (boolean result : writer.get(30, TimeUnit.SECONDS)) {
                    accepted += result ? 1 : 0;
                }
            }
            completer.get(30, TimeUnit.SECONDS);
            // Contention, not luck: the writes raced the completion on one stream.
            assertThat(accepted).as("some writes reached the live stream").isPositive();
        } finally {
            pool.shutdownNow();
        }

        assertThat(registry.hasStream(runId)).as("the completed stream is gone").isFalse();
        assertThat(registry.emit(runId, "approval_required", Map.of()))
                .as("and it stays gone").isFalse();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<Throwable>> consumerCaptor() {
        return ArgumentCaptor.forClass((Class<Consumer<Throwable>>) (Class<?>) Consumer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<Boolean> emitConcurrently(RunStreamRegistry registry, UUID runId,
                                                  int threads, int perThread) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Boolean>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    List<Boolean> results = new ArrayList<>();
                    for (int n = 0; n < perThread; n++) {
                        results.add(registry.emit(runId, "approval_required", Map.of()));
                    }
                    return results;
                }));
            }
            start.countDown();
            List<Boolean> all = new ArrayList<>();
            for (Future<List<Boolean>> future : futures) {
                all.addAll(future.get(30, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }
}
