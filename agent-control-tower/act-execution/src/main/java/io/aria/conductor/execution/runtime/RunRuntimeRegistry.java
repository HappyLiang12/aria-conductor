package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunExecutionBinding;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process, run-keyed registry of the run-owned runtimes a coordinator
 * launched: the frozen spec, the backend, the adapter, the workspace lease, the
 * launched handle and the open session, plus the truthful control facts
 * (verified stop, pending control request, recorded permission asks).
 *
 * <p>It is the quiescence view callers consult ({@link RuntimeActivity}):
 * {@link #writersStopped(UUID)} is true only when a verified stop of this run's
 * writers was observed -- either by a verified stop proof or by a finalization
 * that completed (finalization returns only after its stop verified) -- and
 * {@link #activeRuns(UUID)} lists the runs of an agent whose writers have not
 * been verified stopped. A restart-reconstructed ownership record is never
 * placed here: nothing in this registry outlives the process.
 */
public final class RunRuntimeRegistry implements RuntimeActivity {

    /** A control request that has been issued but not verified by the runtime. */
    public record ControlRequest(ControlState requested, Instant requestedAt, String failureReason) {
    }

    /** One live (or terminal) run-owned runtime. */
    public static final class RunRuntime {

        private final ExecutionSpec spec;
        private final ExecutionBackend backend;
        private final CoreAdapter adapter;
        private final WorkspaceLease lease;
        private final List<UUID> permissionAsks = new CopyOnWriteArrayList<>();

        private volatile CoreCapabilities capabilities;
        private volatile RunExecutionBinding binding;
        private volatile RuntimeHandle handle;
        private volatile CoreSession session;
        private volatile boolean writersVerifiedStopped;
        private volatile ControlRequest pendingControl;
        private volatile String controlState = ControlState.RUNNING.name();
        private volatile boolean finalized;

        RunRuntime(ExecutionSpec spec, ExecutionBackend backend, CoreAdapter adapter, WorkspaceLease lease) {
            this.spec = spec;
            this.backend = backend;
            this.adapter = adapter;
            this.lease = lease;
        }

        public ExecutionSpec spec() {
            return spec;
        }

        /** The run's workspace lease, released only by a finalization with a verified stop. */
        public WorkspaceLease lease() {
            return lease;
        }

        public ExecutionBackend backend() {
            return backend;
        }

        public CoreAdapter adapter() {
            return adapter;
        }

        public CoreCapabilities capabilities() {
            return capabilities;
        }

        public RunExecutionBinding binding() {
            return binding;
        }

        public RuntimeHandle handle() {
            return handle;
        }

        public CoreSession session() {
            return session;
        }

        /** True once a verified stop of this run's writers was observed. */
        public boolean writersVerifiedStopped() {
            return writersVerifiedStopped;
        }

        /** The control state as last verified by the runtime (never an unverified request). */
        public String controlState() {
            return controlState;
        }

        /** The issued-but-unverified control request, if any. */
        public Optional<ControlRequest> pendingControl() {
            return Optional.ofNullable(pendingControl);
        }

        /**
         * True once the run was finalized (stopped, captured, destroyed, released).
         * A terminal record stays readable here; eviction/retention of terminal
         * records is owned by the later lifecycle tasks (T14/T18).
         */
        public boolean finalized() {
            return finalized;
        }

        /** The native permission asks registered for this run, in registration order. */
        public List<UUID> permissionAsks() {
            return List.copyOf(permissionAsks);
        }

        // ---- package-private state transitions, owned by the coordinator ----

        void attach(RunExecutionBinding binding, CoreCapabilities capabilities) {
            this.binding = binding;
            this.capabilities = capabilities;
        }

        void launch(RuntimeHandle handle, CoreSession session) {
            this.handle = handle;
            this.session = session;
        }

        void recordVerifiedControl(String state) {
            this.controlState = state;
            this.pendingControl = null;
        }

        void recordVerifiedStop(String state) {
            this.controlState = state;
            this.writersVerifiedStopped = true;
            this.pendingControl = null;
        }

        void recordPendingControl(ControlRequest request) {
            this.pendingControl = request;
        }

        void recordFinalized() {
            this.finalized = true;
        }

        void rememberPermissionAsk(UUID approvalId) {
            permissionAsks.add(approvalId);
        }

        void forgetPermissionAsk(UUID approvalId) {
            permissionAsks.remove(approvalId);
        }
    }

    private final Map<UUID, RunRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<UUID, List<Consumer<CoreEvent>>> observers = new ConcurrentHashMap<>();

    /** Registers a run-owned runtime for the pre-launch half of the attempt. */
    public RunRuntime register(ExecutionSpec spec, ExecutionBackend backend, CoreAdapter adapter,
            WorkspaceLease lease) {
        RunRuntime runtime = new RunRuntime(spec, backend, adapter, lease);
        runtimes.put(spec.runId(), runtime);
        return runtime;
    }

    /** The live (or terminal) runtime of a run, if this process owns one. */
    public Optional<RunRuntime> find(UUID runId) {
        return Optional.ofNullable(runtimes.get(runId));
    }

    /** Every runtime this process holds, in no defined order. */
    public List<RunRuntime> all() {
        return List.copyOf(runtimes.values());
    }

    /**
     * Forgets a run's record only after its lease was released; callers keep
     * terminal records. Eviction/retention of terminal records (a finalized run
     * stays readable here, {@link RunRuntime#finalized()}) is owned by the later
     * lifecycle tasks (T14/T18), not by this registry.
     */
    public void remove(UUID runId) {
        runtimes.remove(runId);
        observers.remove(runId);
    }

    @Override
    public boolean writersStopped(UUID runId) {
        RunRuntime runtime = runtimes.get(runId);
        return runtime != null && runtime.writersVerifiedStopped();
    }

    @Override
    public Set<UUID> activeRuns(UUID agentId) {
        Set<UUID> active = new java.util.LinkedHashSet<>();
        for (Map.Entry<UUID, RunRuntime> entry : runtimes.entrySet()) {
            RunRuntime runtime = entry.getValue();
            if (agentId.equals(runtime.spec().agentId()) && !runtime.writersVerifiedStopped()) {
                active.add(entry.getKey());
            }
        }
        return Set.copyOf(active);
    }

    /** Subscribes an observer to every core event of the run (T15/T18 consume this seam). */
    public void observe(UUID runId, Consumer<CoreEvent> observer) {
        observers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(observer);
    }

    /** Forwards one core event to the run's observers. */
    public void forward(UUID runId, CoreEvent event) {
        for (Consumer<CoreEvent> observer : observers.getOrDefault(runId, List.of())) {
            observer.accept(event);
        }
    }
}
