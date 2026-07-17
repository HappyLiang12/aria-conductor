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

    public ActionResult executeAction(Action action) {
        if (action == null) {
            throw new IllegalArgumentException("Action must not be null");
        }
        if (!healthy) {
            return ActionResult.failure("ADK runtime unhealthy");
        }
        executedActions.add(action.name());
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
        executedActions.clear();
        handlers.clear();
        defaultHandler = action -> ActionResult.ok("mock-output:" + action.name());
    }
}
