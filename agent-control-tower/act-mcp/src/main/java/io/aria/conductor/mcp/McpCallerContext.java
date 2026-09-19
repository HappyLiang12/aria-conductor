package io.aria.conductor.mcp;

import io.aria.conductor.execution.approval.WorkerScope;

import java.util.Optional;

/**
 * Request-scoped holder for the resolved MCP caller identity (C4 ruling 3).
 *
 * <p>{@link McpTokenFilter} resolves every protected HTTP request to exactly one
 * caller — OPERATOR, WORKER(scope), or a 401 rejection — and binds it here for
 * the duration of the servlet chain. Because the streamable transport invokes
 * tool callbacks on an async thread, the same identity is re-bound around the
 * actual tool invocation from the SDK transport context by
 * {@code McpServerConfig.IdentityBindingToolCallback}. Both seams clear the
 * holder in a {@code finally} block so a pooled thread can never leak identity
 * into the next request. {@code WorkerGovernanceAspect} reads it at the tool
 * invocation seam.
 *
 * <p>Absent value means "no identity was classified" (e.g. an in-process tool
 * call that never passed the HTTP filter). The aspect treats absent as
 * OPERATOR-equivalent, matching the legacy none-mode contract; a caller that is
 * present but not classified is never allowed to degrade silently.
 */
public final class McpCallerContext {

    /** Who the request acted as. */
    public enum Kind {
        OPERATOR,
        WORKER
    }

    /**
     * Resolved caller. A WORKER caller must carry its {@link WorkerScope}; the
     * governance aspect denies a worker without one instead of falling back.
     */
    public record Caller(Kind kind, WorkerScope scope) {

        public static Caller operator() {
            return new Caller(Kind.OPERATOR, null);
        }

        public static Caller worker(WorkerScope scope) {
            return new Caller(Kind.WORKER, scope);
        }

        public boolean isOperator() {
            return kind == Kind.OPERATOR;
        }

        public boolean isWorker() {
            return kind == Kind.WORKER;
        }
    }

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private McpCallerContext() {
    }

    /** Binds the caller for the current request thread; null clears. */
    public static void set(Caller caller) {
        if (caller == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(caller);
        }
    }

    public static Optional<Caller> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Must be called in a {@code finally} block by whoever called {@link #set}. */
    public static void clear() {
        CURRENT.remove();
    }
}
