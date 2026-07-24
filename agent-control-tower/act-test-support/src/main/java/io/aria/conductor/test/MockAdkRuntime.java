package io.aria.conductor.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory simulator for the ADK subprocess runtime.
 * <p>
 * Records every action executed and returns canned {@link ActionResult}s
 * configured per-action-name. Health can be toggled to exercise failure
 * paths without spawning a real subprocess.
 */
public class MockAdkRuntime {

    private volatile boolean healthy = true;
    private final List<String> executedActions = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Function<Action, ActionResult>> handlers = new ConcurrentHashMap<>();
    private Function<Action, ActionResult> defaultHandler =
            action -> ActionResult.ok("mock-output:" + action.name());
    /** Simulated per-action latency in ms (0 = no delay). Supports load-test realism. */
    private volatile long latencyMs = 0L;
    /** When false, executed actions are not recorded (avoids unbounded growth in high-volume tests). */
    private volatile boolean recordingEnabled = true;
    /** Max recorded actions kept (oldest dropped beyond this). Guards memory in load scenarios. */
    private volatile int maxRecordedActions = 10_000;

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Register a per-action handler. Overrides any previously registered
     * handler for the same action name.
     */
    public MockAdkRuntime onAction(String actionName, Function<Action, ActionResult> handler) {
        handlers.put(actionName, handler);
        return this;
    }

    /**
     * Configure the fallback handler used when no per-action handler matches.
     */
    public MockAdkRuntime withDefaultHandler(Function<Action, ActionResult> handler) {
        this.defaultHandler = handler;
        return this;
    }

    /** Inject a fixed artificial latency before each action executes (0 disables). */
    public MockAdkRuntime withLatencyMs(long latencyMs) {
        this.latencyMs = Math.max(0L, latencyMs);
        return this;
    }

    /** Enable/disable action recording (disable for high-volume load scenarios). */
    public MockAdkRuntime withRecording(boolean enabled) {
        this.recordingEnabled = enabled;
        return this;
    }

    /** Cap the number of recorded actions retained (oldest evicted first). */
    public MockAdkRuntime withMaxRecordedActions(int max) {
        this.maxRecordedActions = Math.max(1, max);
        return this;
    }

    public ActionResult executeAction(Action action) {
        if (action == null) {
            throw new IllegalArgumentException("Action must not be null");
        }
        if (!healthy) {
            return ActionResult.failure("ADK runtime unhealthy");
        }
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ActionResult.failure("Interrupted while simulating latency");
            }
        }
        if (recordingEnabled) {
            synchronized (executedActions) {
                executedActions.add(action.name());
                while (executedActions.size() > maxRecordedActions) {
                    executedActions.remove(0);
                }
            }
        }
        Function<Action, ActionResult> handler = handlers.getOrDefault(action.name(), defaultHandler);
        return handler.apply(action);
    }

    public List<String> getExecutedActions() {
        synchronized (executedActions) {
            return List.copyOf(executedActions);
        }
    }

    /**
     * Reset to a healthy runtime with no recorded actions or handlers.
     */
    public void reset() {
        healthy = true;
        latencyMs = 0L;
        recordingEnabled = true;
        maxRecordedActions = 10_000;
        executedActions.clear();
        handlers.clear();
        defaultHandler = action -> ActionResult.ok("mock-output:" + action.name());
    }
}
