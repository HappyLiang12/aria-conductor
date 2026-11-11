package io.aria.conductor.execution.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The run-owned SSE emitters, keyed by run id (spec §5.1 / decision A2): an ask
 * that arrives on the core session's bridge reader thread must reach the
 * emitter of the turn that produced it, which is owned by the engine's virtual
 * thread and therefore unreachable from the ask's call stack.
 *
 * <p>Concurrent by construction. A stream can complete, error, time out or be
 * replaced while a signal is in flight, so removal is always conditional on the
 * emitter the registry still holds -- the emitter's own callbacks and a failed
 * write both use {@code remove(key, value)} -- except {@link #unregister}, which
 * is the run-scoped unbind of the engine's terminal paths and is deliberately
 * authoritative for the run: it unbinds whatever stream the run currently holds.
 * That is unambiguous only because a run binds one emitter
 * ({@code AgentLoopEngine#startRunStream} is the sole {@link #register} site), so
 * no newer emitter can be evicted by an older turn's unbind. An emitter-scoped
 * {@code unregister} would need the emitter at the four call sites, which hold a
 * run id only. No call here ever throws into the engine loop or the bridge
 * reader thread. A completed stream is never resurrected: once its entry is gone
 * nothing re-registers it, and a new turn registers a new emitter.
 */
@Slf4j
@Component
public class RunStreamRegistry {

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Binds a run's stream. Completion, timeout and error all unbind it; the
     * removal is conditional on this exact emitter, so the callbacks of a
     * superseded stream cannot unbind its replacement.
     */
    public void register(UUID runId, SseEmitter emitter) {
        if (runId == null || emitter == null) {
            return;
        }
        emitters.put(runId, emitter);
        emitter.onCompletion(() -> emitters.remove(runId, emitter));
        emitter.onTimeout(() -> emitters.remove(runId, emitter));
        emitter.onError(error -> emitters.remove(runId, emitter));
    }

    /**
     * Unbinds the run's stream on the engine's terminal paths. Idempotent.
     *
     * <p>Run-scoped, not emitter-scoped: it releases whatever emitter the run holds, because
     * these call sites hold a run id and no emitter. The emitter-scoped
     * {@link #unregister(UUID, SseEmitter)} overload is the conditional form for a caller that
     * still owns its emitter; until a terminal path has one, this is the authoritative unbind.
     */
    public void unregister(UUID runId) {
        if (runId == null) {
            return;
        }
        emitters.remove(runId);
    }

    /**
     * Unbinds the run's stream only while it is still this emitter (the conditional form): a
     * superseded stream's owner cannot evict the stream that replaced it.
     */
    public void unregister(UUID runId, SseEmitter emitter) {
        if (runId == null || emitter == null) {
            return;
        }
        emitters.remove(runId, emitter);
    }

    /** True while the run has a stream that has not completed, errored or timed out. */
    public boolean hasStream(UUID runId) {
        return runId != null && emitters.containsKey(runId);
    }

    /**
     * Hands one event to the run's live stream.
     *
     * @return true when the frame was written; false when the run has no live
     *         stream (the normal case for a sync turn and for a closed panel),
     *         which is never an error and never an exception
     */
    public boolean emit(UUID runId, String eventName, Map<String, Object> payload) {
        if (runId == null || eventName == null) {
            return false;
        }
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) {
            return false;
        }
        try {
            // SseEmitter serializes concurrent writes itself, so signals arriving from
            // the bridge reader thread and the run loop cannot interleave inside a frame.
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload != null ? payload : Map.of(), MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            // A disconnected client is not a run failure: drop the stream so no
            // later signal is retried against a dead one.
            emitters.remove(runId, emitter);
            log.debug("Dropped SSE event {} for run {}: {}", eventName, runId, e.getMessage());
            return false;
        }
    }
}
