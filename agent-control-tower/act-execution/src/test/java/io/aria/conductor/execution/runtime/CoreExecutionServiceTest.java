package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.event.RunInputReceivedEvent;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.event.RunWaitingForInputEvent;
import io.aria.conductor.common.event.TurnCompletedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.approval.NativePermission;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmToolCall;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The run coordinator's truthfulness: a control state is persisted only after
 * the matching verified acknowledgement, a prompt completion is never a stop
 * proof, the run-owned handle/session is reused on resume, held permission
 * decisions are delivered only after a resume re-validates them, and the
 * finalization order is the one {@link RunFinalizer} owns.
 */
class CoreExecutionServiceTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID APPROVAL = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final Instant NOW = Instant.now();
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(45));
    /** A deadline short enough for a real scheduler, long enough that a stopped world cannot miss it. */
    private static final Instant NEAR_DEADLINE = NOW.plusSeconds(1);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentExecutionSettings SETTINGS =
            new AgentExecutionSettings("qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT, "C:/work", null);
    /** The frozen settings snapshot exactly as the binding row records it (Task 2's pinned serialization). */
    private static final String SETTINGS_JSON = "{\"coreId\":\"qoder\",\"executionMode\":\"HOST\","
            + "\"workspaceMode\":\"DIRECT\",\"workspacePath\":\"C:/work\",\"workspaceBaseRef\":null}";

    private final List<String> recordedSteps = new ArrayList<>();
    /** CopyOnWriteArrayList: the coordinator publishes from the parked run's thread (task 5 review hygiene). */
    private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;
    private final RunInputCoordinator coordinator = new RunInputCoordinator(eventPublisher);
    private final TaskDeadlineProperties taskDeadlines = new TaskDeadlineProperties();
    private final PermissionCoordinator permissions = mock(PermissionCoordinator.class);
    private final CoreCredentialService credentials = mock(CoreCredentialService.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final RunExecutionBindingRepository bindings = mock(RunExecutionBindingRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final SessionTrajectoryRepository trajectories = mock(SessionTrajectoryRepository.class);
    private final RunRuntimeRegistry runtimes = new RunRuntimeRegistry();
    /** The non-qoder adapter the no-parking gate is asserted against. */
    private final CoreAdapter opencodeAdapter = new RecordingAdapter() {
        @Override
        public String coreId() {
            return "opencode";
        }
    };

    private RecordingBackend backend;
    private RecordingWorkspace workspaces;
    private RecordingSession session;
    private RecordingAdapter adapter;
    private RunExecutionBinding binding;
    private CoreExecutionService service;

    // ------------------------------------------------------------------ doubles

    private final class RecordingBackend implements ExecutionBackend {
        final Deque<StopProof> proofs = new ArrayDeque<>();
        final Deque<ControlAck> pauseAcks = new ArrayDeque<>();
        final Deque<ControlAck> resumeAcks = new ArrayDeque<>();
        final List<StopProof> stopProofs = new ArrayList<>();
        int launchCalls;
        boolean destroyed;
        Runnable duringStopWriters;
        /** Overridden per test: the round-trip migration pin needs a SANDBOX twin. */
        ExecutionMode declaredMode = ExecutionMode.HOST;

        @Override
        public ExecutionMode mode() {
            return declaredMode;
        }

        @Override
        public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
            recordedSteps.add("prepare");
            return new PreparedEnvironment(spec.runId(), ExecutionMode.HOST, "env-1",
                    "C:/work", "C:/runtime/runs/" + spec.runId(), URI.create("http://127.0.0.1:9311/"));
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
            recordedSteps.add("launch");
            launchCalls++;
            return new RuntimeHandle(RUN, ExecutionMode.HOST, environment.environmentId(),
                    "run-owner-identity", environment.endpoint());
        }

        @Override
        public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(pauseAcks.isEmpty()
                    ? new ControlAck(ControlState.RUNNING, false) : pauseAcks.removeFirst());
        }

        @Override
        public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(resumeAcks.isEmpty()
                    ? new ControlAck(ControlState.RUNNING, true) : resumeAcks.removeFirst());
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
            recordedSteps.add("stop-writers");
            StopProof proof = proofs.isEmpty() ? new StopProof(RUN, true) : proofs.removeFirst();
            stopProofs.add(proof);
            if (duringStopWriters != null) {
                duringStopWriters.run();
            }
            return proof;
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            recordedSteps.add("export");
        }

        @Override
        public void destroy(RuntimeHandle handle) {
            recordedSteps.add("destroy");
            destroyed = true;
        }
    }

    private final class RecordingWorkspace implements WorkspaceService {
        final ArtifactBundle bundle = new ArtifactBundle(Path.of("results", RUN.toString()), "sha256-manifest", true);
        StopProof capturedProof;
        StopProof releasedProof;

        @Override
        public WorkspaceLease acquire(ExecutionSpec spec) {
            recordedSteps.add("acquire");
            return new WorkspaceLease(LEASE, spec.runId(), WorkspaceKind.DIRECT,
                    Path.of("C:/work"), Path.of("C:/work"), "C:/runtime/runs/" + spec.runId(), null);
        }

        @Override
        public ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend,
                RuntimeHandle handle, StopProof proof) {
            recordedSteps.add("capture");
            capturedProof = proof;
            return bundle;
        }

        @Override
        public void release(WorkspaceLease lease, StopProof proof) {
            recordedSteps.add("release");
            releasedProof = proof;
        }
    }

    private final class RecordingSession implements CoreSession {
        CoreTask prompted;
        CoreTask secondPrompt;
        CoreTask thirdPrompt;
        /** Turn results the clarification loop consumes in order; null falls back to the default completion. */
        Deque<CoreResult> scriptedResults;
        final List<CoreEvent> events = new ArrayList<>();
        final List<PermissionReply> decided = new ArrayList<>();
        final Deque<ControlAck> pauseAcks = new ArrayDeque<>();
        final Deque<ControlAck> resumeAcks = new ArrayDeque<>();
        RuntimeException promptFailure;
        Runnable duringPrompt;
        /**
         * The event consumer of the prompt, captured so a test can hand the coordinator one core
         * event after the prompt is over -- the exact shape of the progress pump's bounded halt
         * (Lane B), where the fragment already in flight reaches the producer after the run
         * settled. Nothing else reaches the producer's mapping seam.
         */
        Consumer<CoreEvent> promptConsumer;

        @Override
        public String sessionId() {
            return "session-1";
        }

        @Override
        public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> consumer) {
            recordedSteps.add("prompt");
            promptConsumer = consumer;
            if (prompted == null) {
                prompted = task;
            } else if (secondPrompt == null) {
                secondPrompt = task;
            } else if (thirdPrompt == null) {
                thirdPrompt = task;
            }
            events.forEach(consumer);
            if (duringPrompt != null) {
                duringPrompt.run();
            }
            if (promptFailure != null) {
                return CompletableFuture.failedFuture(promptFailure);
            }
            return CompletableFuture.completedFuture(nextResult());
        }

        /** Hands one core event to the prompt's consumer, as the run's progress worker thread does. */
        void deliver(CoreEvent event) {
            Consumer<CoreEvent> consumer = promptConsumer;
            if (consumer == null) {
                throw new IllegalStateException("no prompt consumer was captured; the run never prompted");
            }
            consumer.accept(event);
        }

        /** The next scripted turn result, or the default completion when nothing (more) is scripted. */
        private CoreResult nextResult() {
            CoreResult scripted = scriptedResults == null ? null : scriptedResults.poll();
            return scripted != null ? scripted
                    : new CoreResult("session-1", "final output", usage(), false);
        }

        @Override
        public CompletionStage<ControlAck> pause(Instant deadline) {
            recordedSteps.add("session-pause");
            return CompletableFuture.completedFuture(pauseAcks.isEmpty()
                    ? new ControlAck(ControlState.PAUSED, true) : pauseAcks.removeFirst());
        }

        @Override
        public CompletionStage<ControlAck> resume(Instant deadline) {
            recordedSteps.add("session-resume");
            return CompletableFuture.completedFuture(resumeAcks.isEmpty()
                    ? new ControlAck(ControlState.RUNNING, true) : resumeAcks.removeFirst());
        }

        @Override
        public CompletionStage<ControlAck> cancel(Instant deadline) {
            recordedSteps.add("session-cancel");
            return CompletableFuture.completedFuture(new ControlAck(ControlState.STOPPED, true));
        }

        @Override
        public CompletionStage<Void> decide(PermissionReply reply) {
            recordedSteps.add("decide");
            decided.add(reply);
            return CompletableFuture.completedFuture(null);
        }
    }

    private class RecordingAdapter implements CoreAdapter {
        @Override
        public String coreId() {
            return "qoder";
        }

        @Override
        public CoreCapabilities capabilities(ExecutionMode mode) {
            return new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true);
        }

        @Override
        public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment, SecretBundle creds) {
            recordedSteps.add("launch-profile");
            return new LaunchProfile(List.of("qodercli", "--acp"),
                    Map.of("QODER_PERSONAL_ACCESS_TOKEN", "secret"), environment.workingDirectory());
        }

        @Override
        public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
            recordedSteps.add("open-session");
            return session;
        }
    }

    /** A test clock the class advances itself; the service never reads the wall clock through it. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * The deadline scheduler as a deterministic double: {@code schedule} captures
     * the armed run-deadline target (and its delay) instead of racing a real
     * timer, so a test fires the target exactly when the run's deadline elapses.
     */
    private static final class ManualDeadlineScheduler extends ScheduledThreadPoolExecutor {
        private final List<Instant> scheduledInstants = new ArrayList<>();
        private final Clock instantClock;
        private Runnable target;
        private long delayMillis;
        private volatile boolean cancelled;

        ManualDeadlineScheduler() {
            this(Clock.systemUTC());
        }

        ManualDeadlineScheduler(Clock instantClock) {
            super(1);
            this.instantClock = instantClock;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            this.target = command;
            this.delayMillis = unit.toMillis(delay);
            this.scheduledInstants.add(instantClock.instant().plusMillis(this.delayMillis));
            return new ScheduledFuture<Object>() {
                @Override
                public long getDelay(TimeUnit unit) {
                    return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
                }

                @Override
                public int compareTo(Delayed other) {
                    return 0;
                }

                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    cancelled = true;
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }

                @Override
                public boolean isDone() {
                    return cancelled;
                }

                @Override
                public Object get() {
                    return null;
                }

                @Override
                public Object get(long timeout, TimeUnit unit) {
                    return null;
                }
            };
        }

        /** Runs the captured run-deadline target: the timer firing as scheduled. */
        void fireDeadline() {
            cancelled = false;
            target.run();
        }

        /** Every instant a deadline was armed at, in scheduling order (clock + delay). */
        List<Instant> scheduledInstants() {
            return List.copyOf(scheduledInstants);
        }
    }

    // ------------------------------------------------------------------ fixture

    @BeforeEach
    void setUp() {
        recordedSteps.clear();
        backend = new RecordingBackend();
        workspaces = new RecordingWorkspace();
        session = new RecordingSession();
        adapter = new RecordingAdapter();
        binding = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .settingsJson(SETTINGS_JSON)
                .workspaceKind(WorkspaceKind.DIRECT).workspaceRoot("C:/work").workspaceLeaseId(LEASE)
                .credentialRef("qoder:operator").configurationRevision("rev-1")
                .deadline(DEADLINE).build();
        when(bindings.findById(RUN)).thenReturn(Optional.of(binding));
        when(credentials.resolve("qoder:operator")).thenReturn(new SecretBundle("qoder:operator",
                Map.of("QODER_PERSONAL_ACCESS_TOKEN", "secret")));
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), new ManualDeadlineScheduler(), coordinator, eventPublisher,
                taskDeadlines);
    }

    private ExecutionSpec spec() {
        return new ExecutionSpec(RUN, AGENT, "qoder", ExecutionMode.HOST, SETTINGS,
                "qoder:operator", "rev-1", DEADLINE);
    }

    private CoreTask task() {
        return new CoreTask("system prompt", List.of(LlmMessage.user("first"), LlmMessage.assistant("answer")),
                "do the work");
    }

    /** The usage snapshot the recording session reports for every turn. */
    private static UsageSnapshot usage() {
        return new UsageSnapshot(12L, 7L, null, "efficient");
    }

    /** The non-qoder spec the no-parking gate is asserted against (binding coreId is set to match). */
    private ExecutionSpec opencodeSpec() {
        return new ExecutionSpec(RUN, AGENT, "opencode", ExecutionMode.HOST, SETTINGS,
                "qoder:operator", "rev-1", DEADLINE);
    }

    /** The recorded events of one type, in publication order. */
    private List<Object> published(Class<?> type) {
        return publishedEvents.stream().filter(type::isInstance).toList();
    }

    /** The pre-prompt half of {@link CoreExecutionService#execute}: a live run-owned runtime. */
    private void runOwnedInProcess() {
        service.openRuntime(spec(), adapter);
    }

    /** Blocks until the persisted runtime state reaches {@code expected}; fails the test on timeout. */
    private void awaitRuntimeState(String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(binding.getRuntimeState())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + expected, e);
            }
        }
        throw new AssertionError("runtime state did not reach " + expected + " within " + timeout
                + " (last: " + binding.getRuntimeState() + ")");
    }

    /**
     * The timer thread records the termination failure after its persisted stop,
     * so a run that already observed the state may still be a few instructions
     * ahead of it; consume with a bounded wait instead of racing that last step.
     */
    private RunInputCoordinator.TerminationFailure awaitTerminationFailure(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunInputCoordinator.TerminationFailure failure = coordinator.consumeTerminationFailure(RUN);
            if (failure != null) {
                return failure;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the termination failure", e);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ execute

    @Test
    void executePreparesLaunchesPromptsWithTheFullHistoryAndFinalizesInOrder() {
        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(result.cancelled()).isFalse();
        assertThat(result.usage()).isEqualTo(new UsageSnapshot(12L, 7L, null, "efficient"));
        // The whole accepted history is handed to the core -- context is not replayed action by action.
        assertThat(session.prompted).isEqualTo(new CoreTask("system prompt",
                List.of(LlmMessage.user("first"), LlmMessage.assistant("answer")), "do the work"));
        assertThat(workspaces.capturedProof).isSameAs(backend.stopProofs.get(0));
        assertThat(workspaces.releasedProof).isSameAs(backend.stopProofs.get(0));
        assertThat(backend.launchCalls).isEqualTo(1);
        // The terminal state carries the chosen control strategy; worker tokens are revoked.
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
        assertThat(binding.getRuntimeEnvironmentId()).isEqualTo("env-1");
        assertThat(binding.getRuntimeOwnershipIdentity()).isEqualTo("run-owner-identity");
        assertThat(binding.getRuntimeEndpoint()).isEqualTo("http://127.0.0.1:9311/");
        assertThat(binding.getUsageInputTokens()).isEqualTo(12L);
        assertThat(binding.getUsageOutputTokens()).isEqualTo(7L);
        assertThat(binding.getObservedModel()).isEqualTo("efficient");
        verify(actorTokens).revokeRun(RUN);
        verify(permissions, never()).manualPause(RUN);
        assertThat(service.activeRuns(AGENT)).isEmpty();
        assertThat(service.writersStopped(RUN)).isTrue();
        // The terminal path records the finalized bookkeeping Task 14/18 read.
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
    }

    @Test
    void aRunWithoutAFrozenBindingIsRefusedBeforeAnythingIsPrepared() {
        when(bindings.findById(RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " has no frozen execution binding; refusing to launch"
                        + " without an immutable binding");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    // ------------------------------------------------------------------ frozen workspace/settings match

    @Test
    void aMatchingFrozenWorkspaceAndSettingsSnapshotAreAccepted() {
        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(binding.getWorkspaceLeaseId()).isEqualTo(LEASE);
        assertThat(binding.getWorkspaceRoot()).isEqualTo("C:/work");
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
    }

    @Test
    void aFrozenWorkspaceKindMismatchIsRefusedBeforeAnythingIsAcquired() {
        binding.setWorkspaceKind(WorkspaceKind.WORKTREE);

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with workspace kind WORKTREE but the attempt requests"
                        + " DIRECT; the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aFrozenWorkspaceRootMismatchIsRefusedBeforeAnythingIsAcquired() {
        binding.setWorkspaceRoot("C:/somewhere/else");

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with workspace root C:/somewhere/else but the attempt"
                        + " requests C:/work; the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aFrozenSettingsSnapshotMismatchIsRefusedBeforeAnythingIsAcquired() {
        binding.setSettingsJson(SETTINGS_JSON.replace("C:/work", "C:/a-different-selection"));

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with settings AgentExecutionSettings[coreId=qoder,"
                        + " executionMode=HOST, workspaceMode=DIRECT, workspacePath=C:/a-different-selection,"
                        + " workspaceBaseRef=null] but the attempt requests AgentExecutionSettings[coreId=qoder,"
                        + " executionMode=HOST, workspaceMode=DIRECT, workspacePath=C:/work, workspaceBaseRef=null];"
                        + " the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void anUnreadableFrozenSettingsSnapshotIsRefusedInsteadOfSkipped() {
        binding.setSettingsJson("{not the frozen AgentExecutionSettings snapshot");

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Run " + RUN + " carries an unreadable frozen settings snapshot")
                .hasMessageContaining("the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aFrozenWorkspaceLeaseMismatchIsRefusedAfterTheAttemptsOwnAcquisition() {
        UUID frozenLease = UUID.fromString("00000000-0000-0000-0000-0000000000af");
        binding.setWorkspaceLeaseId(frozenLease);

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with workspace lease " + frozenLease
                        + " but the attempt acquired " + LEASE
                        + "; the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).containsExactly("acquire");
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aPromptFailureStillStopsCapturesDestroysAndReleases() {
        session.promptFailure = new IllegalStateException("the bridge event stream failed");

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " failed: the bridge event stream failed");

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        assertThat(binding.getRuntimeState()).isEqualTo("FAILED/BACKEND_SUSPEND");
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
        verify(actorTokens).revokeRun(RUN);
    }

    // ------------------------------------------------------------------ pause / resume

    @Test
    void anUnverifiedPauseIsNeverPersistedAndCarriesTheExactFailureReason() {
        runOwnedInProcess();
        session.pauseAcks.add(new ControlAck(ControlState.RUNNING, false));

        ControlAck ack = service.pause(RUN).toCompletableFuture().join();

        assertThat(ack).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(binding.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.PAUSED, NOW,
                "Pause refused for run " + RUN + ": the core reported RUNNING (verified=false)"));
        verify(permissions, never()).manualPause(RUN);
    }

    @Test
    void aVerifiedPauseIsPersistedOnceAndHoldsPermissionDelivery() {
        runOwnedInProcess();
        session.pauseAcks.add(new ControlAck(ControlState.PAUSED, true));

        ControlAck ack = service.pause(RUN).toCompletableFuture().join();

        assertThat(ack).isEqualTo(new ControlAck(ControlState.PAUSED, true));
        assertThat(binding.getRuntimeState()).isEqualTo("PAUSED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).isEmpty();
        verify(permissions).manualPause(RUN);
    }

    @Test
    void resumeReusesTheSameHandleAndDeliversHeldDecisionsOnlyAfterRevalidating() {
        // A native permission ask registered while the run was live, then a manual pause.
        session.events.add(new CoreEvent("permission.request", RUN, "session-1", "7",
                "{\"requestId\":7,\"toolName\":\"write_file\",\"options\":"
                        + "[{\"optionId\":\"proceed_once\",\"kind\":\"allow_once\"},"
                        + "{\"optionId\":\"cancel\",\"kind\":\"reject_once\"}]}"));
        when(permissions.register(any())).thenReturn(APPROVAL);
        // While manually paused the decision is recorded, not delivered: only resume releases it.
        when(permissions.deliverPending(APPROVAL)).thenReturn(Optional.empty());
        PermissionReply heldReply = new PermissionReply(RUN, "session-1", "7", "proceed_once");
        session.duringPrompt = () -> {
            assertThat(service.pause(RUN).toCompletableFuture().join())
                    .isEqualTo(new ControlAck(ControlState.PAUSED, true));
            when(permissions.deliverPending(APPROVAL)).thenReturn(Optional.of(heldReply));
            assertThat(service.resume(RUN).toCompletableFuture().join())
                    .isEqualTo(new ControlAck(ControlState.RUNNING, true));
        };

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(session.decided).containsExactly(heldReply);
        assertThat(backend.launchCalls).isEqualTo(1); // the same run-owned handle/session, never relaunched
        verify(permissions).manualPause(RUN);
        verify(permissions).manualResume(RUN);
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
    }

    @Test
    void aDecidedNativeReplyIsHandedToTheRunOwnedSessionAndNowhereElse() {
        runOwnedInProcess();

        PermissionReply reply = new PermissionReply(RUN, "session-1", "7", "proceed_once");
        service.deliver(reply);

        // The direct (non-held) decision reaches the one session waiting for it.
        assertThat(session.decided).containsExactly(reply);
        verify(permissions, never()).deliverPending(any());
    }

    @Test
    void aDecidedReplyForARunThisProcessDoesNotOwnIsDroppedInsteadOfRouted() {
        // No run-owned runtime in this process: there is no session to hand the
        // reply to, and it is never routed to another run or fabricated.
        service.deliver(new PermissionReply(RUN, "session-1", "7", "proceed_once"));

        assertThat(session.decided).isEmpty();
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelPersistsCancelledOnlyAfterTheMatchingVerifiedStopProof() {
        runOwnedInProcess();
        backend.proofs.add(new StopProof(RUN, false));

        ControlAck refused = service.cancel(RUN).toCompletableFuture().join();

        assertThat(refused).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(binding.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW,
                "Stop refused for run " + RUN + ": the backend reported allWritersStopped=false"));
        assertThat(backend.destroyed).isFalse();
        verify(actorTokens, never()).revokeRun(RUN);

        backend.proofs.add(new StopProof(RUN, true));
        ControlAck cancelled = service.cancel(RUN).toCompletableFuture().join();

        assertThat(cancelled).isEqualTo(new ControlAck(ControlState.STOPPED, true));
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).isEmpty();
        assertThat(service.writersStopped(RUN)).isTrue();
        assertThat(service.activeRuns(AGENT)).isEmpty();
        // A verified cancel stops the writers but is not a finalization: capture/destroy/release is pending.
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isFalse();
        verify(actorTokens).revokeRun(RUN);
    }

    @Test
    void aTerminationReasonIsRecordedOnlyAfterTheStopIsVerified() {
        runOwnedInProcess();
        String reason = "Run deadline reached at 2026-10-20T12:00:00Z"
                + " (aria.tasks.deadline-minutes in Configure > System Config).";
        backend.proofs.add(new StopProof(RUN, false));

        ControlAck refused = service.cancel(RUN, reason, "RUN_DEADLINE_REACHED", "SYSTEM_CONFIG")
                .toCompletableFuture().join();

        assertThat(refused.verified()).isFalse();
        assertThat(coordinator.consumeTerminationFailure(RUN))
                .as("a refused stop leaves the run alive, so it must not carry the deadline failure")
                .isNull();

        backend.proofs.add(new StopProof(RUN, true));
        ControlAck stopped = service.cancel(RUN, reason, "RUN_DEADLINE_REACHED", "SYSTEM_CONFIG")
                .toCompletableFuture().join();

        assertThat(stopped).isEqualTo(new ControlAck(ControlState.STOPPED, true));
        RunInputCoordinator.TerminationFailure failure = coordinator.consumeTerminationFailure(RUN);
        assertThat(failure).isNotNull();
        assertThat(failure.message()).isEqualTo(reason);
        assertThat(failure.code()).isEqualTo("RUN_DEADLINE_REACHED");
        assertThat(failure.surface()).isEqualTo("SYSTEM_CONFIG");
    }

    @Test
    void aForeignStopProofNeverPersistsACancellation() {
        runOwnedInProcess();
        UUID otherRun = UUID.fromString("00000000-0000-0000-0000-0000000000a8");
        backend.proofs.add(new StopProof(otherRun, true));
        backend.proofs.add(new StopProof(otherRun, true));

        ControlAck ack = service.cancel(RUN).toCompletableFuture().join();

        assertThat(ack).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(binding.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW,
                "Stop refused for run " + RUN + ": the backend returned a stop proof for run " + otherRun
                        + ", not for this run"));
        verify(actorTokens, never()).revokeRun(RUN);
        assertThat(backend.destroyed).isFalse();
    }

    @Test
    void aRefusedCancelOfAPausedRunAcksTheFrozenPausedStateNeverRunning() {
        runOwnedInProcess();
        session.pauseAcks.add(new ControlAck(ControlState.PAUSED, true));
        assertThat(service.pause(RUN).toCompletableFuture().join())
                .isEqualTo(new ControlAck(ControlState.PAUSED, true));
        backend.proofs.add(new StopProof(RUN, false));

        ControlAck refused = service.cancel(RUN).toCompletableFuture().join();

        // The frozen verified state is PAUSED, so the refusal acknowledges PAUSED (verified=false):
        // the ack never claims the paused run is RUNNING, and nothing was persisted.
        assertThat(refused).isEqualTo(new ControlAck(ControlState.PAUSED, false));
        assertThat(binding.getRuntimeState()).isEqualTo("PAUSED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW,
                "Stop refused for run " + RUN + ": the backend reported allWritersStopped=false"));
        assertThat(service.writersStopped(RUN)).isFalse();
        verify(actorTokens, never()).revokeRun(RUN);
        assertThat(backend.destroyed).isFalse();
    }

    @Test
    void aCancelledPromptFinishesThroughTheFinalizerAndReportsCancellation() {
        session.duringPrompt = () -> assertThat(service.cancel(RUN).toCompletableFuture().join())
                .isEqualTo(new ControlAck(ControlState.STOPPED, true));

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.cancelled()).isTrue();
        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        // The cancel's own stop, then the finalizer's re-stop before capture/destroy/release.
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "session-cancel", "stop-writers", "stop-writers",
                "capture", "destroy", "release");
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true), new StopProof(RUN, true));
        assertThat(workspaces.capturedProof).isEqualTo(new StopProof(RUN, true));
        // Once at the verified cancel, once on the terminal path; revocation is idempotent.
        verify(actorTokens, times(2)).revokeRun(RUN);
    }

    @Test
    void theRunDeadlineTimerCancelsAnInFlightPromptThroughTheVerifiedStopPath() {
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler();
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, taskDeadlines);
        // The frozen deadline elapses while the prompt is in flight: the timer target fires now.
        session.duringPrompt = scheduler::fireDeadline;

        CoreResult result = service.execute(spec(), adapter, task());

        // The timer target drove exactly the operator cancel path: cooperative session cancel,
        // then a verified stop proof, then the finalizer's re-stop before capture/destroy/release.
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "session-cancel", "stop-writers", "stop-writers",
                "capture", "destroy", "release");
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true), new StopProof(RUN, true));
        assertThat(result.cancelled()).isTrue();
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).isEmpty();
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
        verify(actorTokens, times(2)).revokeRun(RUN);
        // The task was armed from the injected clock and the frozen deadline, then cancelled in the finally.
        assertThat(scheduler.delayMillis).isEqualTo(Duration.ofMinutes(45).toMillis());
        assertThat(scheduler.cancelled).isTrue();
        // The deadline is recorded with its repair identity so the settling run can
        // tell the operator which System Config key to change.
        RunInputCoordinator.TerminationFailure deadlineFailure = coordinator.consumeTerminationFailure(RUN);
        assertThat(deadlineFailure).isNotNull();
        assertThat(deadlineFailure.code()).isEqualTo("RUN_DEADLINE_REACHED");
        assertThat(deadlineFailure.surface()).isEqualTo("SYSTEM_CONFIG");
        assertThat(deadlineFailure.message())
                .contains("Run deadline reached at")
                .contains("aria.tasks.deadline-minutes in Configure > System Config");
    }

    /**
     * R-01: the manual scheduler above proves the timer target's behavior but not
     * that the real executor ever fires it. Here the executor is real, the frozen
     * deadline is a second away on the service's clock, and the prompt blocks
     * until the timer thread has driven the verified stop.
     */
    @Test
    void theRealDeadlineTimerStopsAnInFlightPromptAndRecordsTheFailureIdentity() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "deadline-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                    new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                    CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, taskDeadlines);
            binding.setDeadline(NEAR_DEADLINE);
            ExecutionSpec nearDeadlineSpec = new ExecutionSpec(RUN, AGENT, "qoder", ExecutionMode.HOST, SETTINGS,
                    "qoder:operator", "rev-1", NEAR_DEADLINE);
            session.duringPrompt = () -> awaitRuntimeState("CANCELLED/BACKEND_SUSPEND", Duration.ofSeconds(10));

            CoreResult result = service.execute(nearDeadlineSpec, adapter, task());

            assertThat(result.cancelled()).isTrue();
            assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
            assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                    "open-session", "prompt", "session-cancel", "stop-writers", "stop-writers",
                    "capture", "destroy", "release");
            assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true), new StopProof(RUN, true));
            assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
            RunInputCoordinator.TerminationFailure failure = awaitTerminationFailure(Duration.ofSeconds(5));
            assertThat(failure).isNotNull();
            assertThat(failure.code()).isEqualTo("RUN_DEADLINE_REACHED");
            assertThat(failure.surface()).isEqualTo("SYSTEM_CONFIG");
            assertThat(failure.message()).startsWith("Run deadline reached at " + NEAR_DEADLINE);
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * The core session enforces the same turn deadline the coordinator timer
     * does, so either enforcer can win. Here the session's own timeout wins (the
     * timer target never fires): the run must still settle CANCELLED with the
     * deadline identity instead of a generic FAILED.
     */
    @Test
    void aSessionTimeoutAtTheDeadlineSettlesCancelledWithTheDeadlineIdentity() {
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler();
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, taskDeadlines);
        binding.setDeadline(NOW);
        ExecutionSpec elapsed = new ExecutionSpec(RUN, AGENT, "qoder", ExecutionMode.HOST, SETTINGS,
                "qoder:operator", "rev-1", NOW);
        session.promptFailure = new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                "The prompt of run " + RUN + " exceeded the run deadline " + NOW);

        CoreResult result = service.execute(elapsed, adapter, task());

        assertThat(result.cancelled()).isTrue();
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        RunInputCoordinator.TerminationFailure failure = coordinator.consumeTerminationFailure(RUN);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("RUN_DEADLINE_REACHED");
        assertThat(failure.surface()).isEqualTo("SYSTEM_CONFIG");
        assertThat(failure.message()).startsWith("Run deadline reached at " + NOW);
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
    }

    /**
     * R-01 companion: when the deadline's stop is refused the run keeps running,
     * completes on its own, and must carry no deadline identity -- the reason is
     * recorded only after a verified stop.
     */
    @Test
    void aRefusedDeadlineStopLeavesTheCompletedRunWithoutAFailureIdentity() {
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler();
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, taskDeadlines);
        backend.proofs.add(new StopProof(RUN, false));
        session.duringPrompt = scheduler::fireDeadline;

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.cancelled()).isFalse();
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
        assertThat(coordinator.consumeTerminationFailure(RUN))
                .as("a refused deadline stop leaves the run alive, so it must not carry the deadline failure")
                .isNull();
        // The refused stop, then the finalizer's verified re-stop that clears the pending control.
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, false), new StopProof(RUN, true));
        assertThat(service.pendingControl(RUN)).isEmpty();
    }

    @Test
    void anAbortedLaunchRetriesOnlyInsideItsOneHoistedCleanupWindow() {
        MutableClock advancing = new MutableClock(NOW);
        CoreExecutionService abortService = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)),
                workspaces, new RunFinalizer(workspaces, advancing), runtimes, permissions, credentials,
                actorTokens, bindings, advancing, Duration.ofMinutes(5));
        abortService.openRuntime(spec(), adapter);
        RunRuntimeRegistry.RunRuntime runtime = runtimes.find(RUN).orElseThrow();
        // The stop stays refused, and the first attempt burns the whole window: a window
        // recomputed per attempt would mint a fresh 5 minutes and retry outside the bound
        // abortLaunch was entered with. The clock is advanced by the stop itself.
        backend.proofs.add(new StopProof(RUN, false));
        backend.duringStopWriters = () -> advancing.advance(Duration.ofMinutes(6));

        abortService.abortLaunch(runtime, runtime.lease());

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "stop-writers"); // exactly one attempt, inside the hoisted window
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, false));
        assertThat(backend.destroyed).isFalse();
        assertThat(workspaces.releasedProof).isNull();
        assertThat(runtimes.find(RUN)).isPresent(); // the fail-closed record outlives the refused abort
        assertThat(abortService.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW.plus(Duration.ofMinutes(6)),
                "Launch abort for run " + RUN + ": the writers were not verified stopped, so the environment"
                        + " and the workspace lease are left in place (destroy after a refused stop is terminal)"));
    }

    // ------------------------------------------------- the launch window (#111 fix round 2)

    /**
     * C1: a real core process exists from the moment {@code backend.launch} returned, and the
     * registry record is coordinator-owned for the whole launch window -- including the
     * serve-readiness wait inside {@code adapter.open}, where no session exists yet. A cancel
     * arriving there must stop that process, never be answered as a recorded CANCELLED.
     */
    @Test
    void aLaunchedRuntimeIsCoordinatorOwnedBeforeItsSessionOpens() {
        CoordinatedRunRuntimeControl runtimeControl = runtimeControl(service);
        boolean[] ownedInsideTheLaunchWindow = new boolean[1];
        boolean[] handleAttachedInsideTheLaunchWindow = new boolean[1];
        adapter = new RecordingAdapter() {
            @Override
            public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
                recordedSteps.add("open-session");
                // The serve-readiness wait happens here: the process is live, the session is not.
                handleAttachedInsideTheLaunchWindow[0] = runtimes.find(RUN).orElseThrow().handle() != null;
                ownedInsideTheLaunchWindow[0] = runtimeControl.owns(AGENT, RUN);
                return session;
            }
        };

        service.openRuntime(spec(), adapter);

        assertThat(handleAttachedInsideTheLaunchWindow[0])
                .as("the launched handle is attached the moment the process exists")
                .isTrue();
        assertThat(ownedInsideTheLaunchWindow[0])
                .as("the coordinator owns a run whose core process is live but whose session has not opened")
                .isTrue();
    }

    /**
     * C1: the cancel taken inside the launch window stops the launched core through the verified
     * path (stop -> destroy -> release), records CANCELLED with the coordinator, and the in-flight
     * launch never opens a prompt on the core it was told to stop.
     */
    @Test
    void aCancelTakenInsideTheLaunchWindowStopsTheLaunchedCoreAndNeverPrompts() {
        CoordinatedRunRuntimeControl runtimeControl = runtimeControl(service);
        adapter = new RecordingAdapter() {
            @Override
            public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
                recordedSteps.add("open-session");
                runtimeControl.cancel(RUN); // the operator's cancel lands in the launch window
                return session;             // ... while the serve-ready wait is still returning
            }
        };

        assertThatThrownBy(() -> service.openRuntime(spec(), adapter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already terminal");

        assertThat(session.prompted).as("a cancelled launch never prompts its core").isNull();
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "stop-writers", "destroy", "release");
        assertThat(backend.destroyed).isTrue();
        assertThat(workspaces.releasedProof).isEqualTo(new StopProof(RUN, true));
        assertThat(runtimes.find(RUN)).isEmpty();
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        verify(actorTokens).revokeRun(RUN);
    }

    /**
     * C2: the session-open failure after {@code backend.launch} leaves no orphan -- the launched
     * handle is stopped, destroyed and its lease released, asserted through the recorded calls on
     * the coordinator's own doubles.
     */
    @Test
    void aSessionOpenFailureAfterLaunchStopsDestroysAndReleasesTheLaunchedCore() {
        adapter = new RecordingAdapter() {
            @Override
            public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
                recordedSteps.add("open-session");
                throw new IllegalStateException("the serve never became ready");
            }
        };

        assertThatThrownBy(() -> service.openRuntime(spec(), adapter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the serve never became ready");

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "stop-writers", "destroy", "release");
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true));
        assertThat(backend.destroyed).isTrue();
        assertThat(workspaces.releasedProof).isEqualTo(new StopProof(RUN, true));
        assertThat(runtimes.find(RUN)).isEmpty();
    }

    /**
     * Ruling 3: a cancel of a run whose launch is still in flight before anything was launched is
     * an honest refusal -- no handle exists, so no writer stop can be proven -- and the pending
     * control carries that exact reason.
     */
    @Test
    void aCancelBeforeTheHandleExistsRefusesInsteadOfClaimingAnUnprovenStop() {
        RunRuntimeRegistry.RunRuntime runtime = runtimes.register(spec(), backend, adapter,
                workspaces.acquire(spec()));
        runtime.attach(binding, adapter.capabilities(ExecutionMode.HOST));

        ControlAck ack = service.cancel(RUN).toCompletableFuture().join();

        assertThat(ack.verified()).isFalse();
        assertThat(service.pendingControl(RUN)).get()
                .extracting(RunRuntimeRegistry.ControlRequest::failureReason)
                .asString().contains("had not launched yet");
        assertThat(backend.stopProofs).isEmpty();
        assertThat(backend.destroyed).isFalse();
        assertThat(binding.getRuntimeState()).isNull();
    }

    /** The run store's control port over one real coordinator instance. */
    private CoordinatedRunRuntimeControl runtimeControl(CoreExecutionService coordinator) {
        org.springframework.beans.factory.ObjectProvider<CoreExecutionService> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public CoreExecutionService getObject() {
                        return coordinator;
                    }

                    @Override
                    public CoreExecutionService getIfAvailable() {
                        return coordinator;
                    }
                };
        return new CoordinatedRunRuntimeControl(provider);
    }

    /** I4: the coordinator answers ownership from its live registry record, for one agent's run. */
    @Test
    void ownsRunIsTheCoordinatorsRegistryPresence() {
        runOwnedInProcess();

        assertThat(service.ownsRun(AGENT, RUN)).isTrue();
        assertThat(service.ownsRun(UUID.randomUUID(), RUN)).isFalse();
        assertThat(service.ownsRun(AGENT, UUID.randomUUID())).isFalse();
        assertThat(service.ownsRun(AGENT, null)).isFalse();

        assertThat(service.cancel(RUN).toCompletableFuture().join())
                .isEqualTo(new ControlAck(ControlState.STOPPED, true));
        assertThat(service.ownsRun(AGENT, RUN))
                .as("a run whose writers were verified stopped is no longer a live owned run")
                .isFalse();
    }

    /**
     * Ruling 3: a cancel the run store recorded while the runtime was still unregistered leaves no
     * live core behind -- the attempt re-reads the committed row before opening its session, stops
     * and releases what its launch created, and never prompts.
     */
    @Test
    void anInFlightLaunchObservesATerminalCommittedRowAndNeverPrompts() {
        CoreExecutionService runStoreAware = serviceWithCommittedStatus(RunStatus.CANCELLED);

        assertThatThrownBy(() -> runStoreAware.openRuntime(spec(), adapter))
                .isInstanceOf(RunTerminatedDuringLaunchException.class)
                .hasMessageContaining("already terminal");

        assertThat(session.prompted).as("a terminal committed row is never prompted past").isNull();
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "stop-writers", "destroy", "release");
        assertThat(recordedSteps).as("the session of a stopped core is never opened").doesNotContain("open-session");
        assertThat(backend.destroyed).isTrue();
        assertThat(workspaces.releasedProof).isEqualTo(new StopProof(RUN, true));
        assertThat(runtimes.find(RUN)).isEmpty();
    }

    /**
     * Ruling 3, the second half: the row turning terminal while the session opens (the serve-ready
     * wait) is caught before the first prompt, and the stop-and-release runs once.
     */
    @Test
    void aCommittedTerminalRowAppearingWhileTheSessionOpensStopsTheLaunchBeforeAnyPrompt() {
        java.util.concurrent.atomic.AtomicBoolean terminated = new java.util.concurrent.atomic.AtomicBoolean();
        CoreExecutionService runStoreAware = new CoreExecutionService(
                new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), new ManualDeadlineScheduler(), coordinator, eventPublisher,
                taskDeadlines, null, null, null,
                ignored -> terminated.get() ? RunStatus.CANCELLED : RunStatus.RUNNING);
        adapter = new RecordingAdapter() {
            @Override
            public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
                recordedSteps.add("open-session");
                terminated.set(true); // the operator's cancel commits while the serve is becoming ready
                return session;
            }
        };

        assertThatThrownBy(() -> runStoreAware.openRuntime(spec(), adapter))
                .isInstanceOf(RunTerminatedDuringLaunchException.class)
                .hasMessageContaining("already terminal");

        assertThat(session.prompted).isNull();
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "stop-writers", "destroy", "release");
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true));
        assertThat(runtimes.find(RUN)).isEmpty();
    }

    /** The coordinator with a run store that reports one committed status for every read. */
    private CoreExecutionService serviceWithCommittedStatus(RunStatus committed) {
        return new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), new ManualDeadlineScheduler(), coordinator, eventPublisher,
                taskDeadlines, null, null, null, ignored -> committed);
    }

    // ------------------------------------------------------------------ events

    @Test
    void everyCoreEventIsForwardedToTheObserverInOrder() {
        session.events.add(new CoreEvent("session.update", RUN, "session-1", "1", "{\"seq\":1}"));
        session.events.add(new CoreEvent("prompt.result", RUN, "session-1", "1", "{\"stopReason\":\"end\"}"));
        List<CoreEvent> observed = new ArrayList<>();
        service.observe(RUN, observed::add);

        service.execute(spec(), adapter, task());

        assertThat(observed).containsExactly(
                new CoreEvent("session.update", RUN, "session-1", "1", "{\"seq\":1}"),
                new CoreEvent("prompt.result", RUN, "session-1", "1", "{\"stopReason\":\"end\"}"));
    }

    @Test
    void anOpencodeProgressCoreEventIsMappedToExactlyOneRunProgressEvent() {
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"TOOL_CALL\",\"content\":\"listing files\",\"toolName\":\"bash\","
                        + "\"seq\":7,\"iteration\":0}"));

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        List<RunProgressEvent> progress = publishedEvents.stream()
                .filter(RunProgressEvent.class::isInstance)
                .map(RunProgressEvent.class::cast)
                .toList();
        assertThat(progress).hasSize(1);
        RunProgressEvent event = progress.get(0);
        assertThat(event.getSource()).isSameAs(service);
        assertThat(event.getRunId()).isEqualTo(RUN);
        assertThat(event.getAgentId()).isEqualTo(AGENT);
        assertThat(event.getIteration()).isZero();
        assertThat(event.getKind()).isEqualTo(RunProgressEvent.Kind.TOOL_CALL);
        assertThat(event.getContent()).isEqualTo("listing files");
        assertThat(event.getToolName()).isEqualTo("bash");
        assertThat(event.getSeq()).isEqualTo(7);
    }

    @Test
    void anUnknownMissingKindOrMalformedProgressPayloadIsDroppedWithoutPublishingOrBreakingTheRun() {
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"DANCE\",\"content\":\"nope\",\"toolName\":null,\"seq\":1,\"iteration\":0}"));
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"content\":\"no kind member\",\"toolName\":null,\"seq\":1,\"iteration\":0}"));
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null, "{not json"));

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(published(RunProgressEvent.class)).isEmpty();
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
    }

    @Test
    void progressWithANonPositiveOrMissingSeqIsDropped() {
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"THINKING\",\"content\":\"zero\",\"toolName\":null,\"seq\":0,\"iteration\":0}"));
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"THINKING\",\"content\":\"negative\",\"toolName\":null,\"seq\":-3,\"iteration\":0}"));
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"THINKING\",\"content\":\"missing\",\"toolName\":null,\"iteration\":0}"));

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(published(RunProgressEvent.class))
                .as("seq is the dashboard dedupe key and the replay order; 0/missing is not an identity")
                .isEmpty();
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
    }

    @Test
    void aProgressEventDoesNotInterfereWithPermissionHandling() {
        session.events.add(new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"THINKING\",\"content\":\"considering\",\"toolName\":null,"
                        + "\"seq\":3,\"iteration\":0}"));
        session.events.add(new CoreEvent("permission.request", RUN, "session-1", "7",
                "{\"requestId\":7,\"toolName\":\"write_file\",\"options\":"
                        + "[{\"optionId\":\"proceed_once\",\"kind\":\"allow_once\"},"
                        + "{\"optionId\":\"cancel\",\"kind\":\"reject_once\"}]}"));
        when(permissions.register(any())).thenReturn(APPROVAL);

        service.execute(spec(), adapter, task());

        ArgumentCaptor<NativePermission> ask = ArgumentCaptor.forClass(NativePermission.class);
        verify(permissions).register(ask.capture());
        assertThat(ask.getValue().runId()).isEqualTo(RUN);
        assertThat(ask.getValue().requestId()).isEqualTo("7");
        assertThat(ask.getValue().toolName()).isEqualTo("write_file");
        assertThat(ask.getValue().options()).hasSize(2);
        assertThat(runtimes.find(RUN).orElseThrow().permissionAsks()).containsExactly(APPROVAL);
        List<RunProgressEvent> progress = publishedEvents.stream()
                .filter(RunProgressEvent.class::isInstance)
                .map(RunProgressEvent.class::cast)
                .toList();
        assertThat(progress).hasSize(1);
        assertThat(progress.get(0).getKind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(progress.get(0).getContent()).isEqualTo("considering");
        assertThat(progress.get(0).getSeq()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ progress for a settled run

    /** One {@code opencode.progress} core event in exactly the wire shape the run's pump emits. */
    private static CoreEvent progressEvent(long seq) {
        return new CoreEvent("opencode.progress", RUN, "session-1", null,
                "{\"kind\":\"THINKING\",\"content\":\"fragment-" + seq + "\",\"toolName\":null,"
                        + "\"seq\":" + seq + ",\"iteration\":0}");
    }

    /** The seq of every {@link RunProgressEvent} published so far, in publication order. */
    private List<Long> publishedProgressSeqs() {
        return publishedEvents.stream()
                .filter(RunProgressEvent.class::isInstance)
                .map(RunProgressEvent.class::cast)
                .map(RunProgressEvent::getSeq)
                .toList();
    }

    /**
     * Review follow-up A1, closed at the producer: the pump's halt is bounded (Lane B deliberately
     * trades that for a terminal result never blocked behind progress persistence), so the
     * fragment already in flight can reach the producer after the run settled. A cancel whose stop
     * the backend verified is a fact this coordinator holds truthfully at that instant, so the
     * settled run's fragment is never persisted or broadcast -- while the same run's live mid-run
     * progress publishes exactly as before.
     */
    @Test
    void progressRaisedAfterAVerifiedStopOfTheRunIsNotPublished() {
        session.events.add(progressEvent(5)); // ordinary mid-run progress, before the cancel
        session.duringPrompt = () -> {
            assertThat(service.cancel(RUN).toCompletableFuture().join())
                    .as("the operator cancel is answered by the verified stop, as production does")
                    .isEqualTo(new ControlAck(ControlState.STOPPED, true));
            assertThat(runtimes.find(RUN).orElseThrow().writersVerifiedStopped())
                    .as("the verified stop is the settlement fact the producer guard rests on")
                    .isTrue();
            session.deliver(progressEvent(6)); // the fragment in flight when the bounded halt returned
        };

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.cancelled()).isTrue();
        assertThat(publishedProgressSeqs())
                .as("live progress publishes; a fragment of a run whose writers were verified stopped does not")
                .containsExactly(5L);
    }

    /**
     * The same window on the finalize side: once {@link RunFinalizer} returned, the run's writers
     * are verified stopped and the run is finalized, so a late fragment of its pump is dropped at
     * the producer instead of landing after the terminal row.
     */
    @Test
    void progressRaisedAfterTheRunWasFinalizedIsNotPublished() {
        session.events.add(progressEvent(5));

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        RunRuntimeRegistry.RunRuntime settled = runtimes.find(RUN).orElseThrow();
        assertThat(settled.finalized()).as("the one terminal path finalized this run").isTrue();
        assertThat(settled.writersVerifiedStopped()).as("and verified its writers stopped").isTrue();

        session.deliver(progressEvent(6));

        assertThat(publishedProgressSeqs()).containsExactly(5L);
    }

    /**
     * A terminal row written by a writer this coordinator never saw -- the run store's recorded
     * cancel, a housekeeping sweep, another settling path -- settles the run just the same, so the
     * producer guard consults the committed status through the projection read (never a cached
     * entity) and drops the fragment even though no stop was verified here.
     */
    @Test
    void progressRaisedForATerminalCommittedRowIsNotPublishedWithoutAVerifiedStopHere() {
        java.util.concurrent.atomic.AtomicReference<RunStatus> committed =
                new java.util.concurrent.atomic.AtomicReference<>(RunStatus.RUNNING);
        CoreExecutionService runStoreAware = new CoreExecutionService(
                new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), new ManualDeadlineScheduler(), coordinator, eventPublisher,
                taskDeadlines, null, null, null, ignored -> committed.get());
        session.events.add(progressEvent(5));
        session.duringPrompt = () -> {
            committed.set(RunStatus.CANCELLED); // the row turned terminal somewhere else
            RunRuntimeRegistry.RunRuntime live = runtimes.find(RUN).orElseThrow();
            assertThat(live.writersVerifiedStopped())
                    .as("this coordinator verified no stop, so the drop must come from the committed row")
                    .isFalse();
            assertThat(live.finalized()).isFalse();
            session.deliver(progressEvent(6));
        };

        runStoreAware.execute(spec(), adapter, task());

        assertThat(publishedProgressSeqs()).containsExactly(5L);
    }

    // ------------------------------------------------------------------ clarification waiting loop

    @Test
    void questionEndingTurnParksForTheAnswerAndRepromptsTheSameSessionWithoutFinalizing() throws Exception {
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Partial progress\n[NEED-INPUT] Which database?", usage(), false),
                new CoreResult("session-1", "All done", usage(), false)));

        // The answer arrives from another thread, as the REST route would.
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !published(RunWaitingForInputEvent.class).isEmpty());
            coordinator.submitAnswer(RUN, "postgres");
        });
        answerer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        answerer.join();

        assertThat(result.finalOutput()).isEqualTo("All done");
        // The answer path's final result is the follow-up turn — a turn the
        // engine has NOT seen before, so the final recording must include it.
        assertThat(result.turnAlreadyAccounted()).isFalse();
        // One launch, one session, TWO prompts -- same session, no re-open, no finalize in between.
        assertThat(recordedSteps).containsSubsequence("launch", "open-session", "prompt", "prompt", "stop-writers");
        assertThat(recordedSteps.lastIndexOf("prompt")).isGreaterThan(recordedSteps.indexOf("open-session"));
        assertThat(backend.launchCalls).isEqualTo(1);
        assertThat(recordedSteps).containsExactlyInAnyOrder("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "prompt", "stop-writers", "capture", "destroy", "release");
        // The follow-up task is the bare answer: session context, no re-sent system/history.
        assertThat(session.secondPrompt.userPrompt()).isEqualTo("postgres");
        assertThat(session.secondPrompt.systemPrompt()).isBlank();
        assertThat(session.secondPrompt.history()).isEmpty();
        // The engine was told about the waiting state, the intermediate turn and the answer.
        assertThat(published(RunWaitingForInputEvent.class)).isNotEmpty();
        assertThat(published(TurnCompletedEvent.class)).isNotEmpty();
        assertThat(published(RunInputReceivedEvent.class)).isNotEmpty();
    }

    @Test
    void finalizeSignalEndsTheRunWithTheQuestionTurnAsTheResult() throws Exception {
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Hmm\n[NEED-INPUT] Continue?", usage(), false)));
        Thread finalizer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !published(RunWaitingForInputEvent.class).isEmpty());
            coordinator.requestFinalize(RUN);
        });
        finalizer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        finalizer.join();

        assertThat(result.finalOutput()).contains("[NEED-INPUT] Continue?");
        assertThat(result.cancelled()).isFalse();
        // The question turn was already accounted when its TurnCompletedEvent was
        // published; the returned result IS that turn, so it must be marked — the
        // engine's final recording must not repeat it.
        assertThat(result.turnAlreadyAccounted()).isTrue();
        // The park really happened: the operator's finalize signal woke a run the engine
        // had announced as waiting, not a run that had already finished on its own.
        assertThat(published(RunWaitingForInputEvent.class)).isNotEmpty();
        // The parked run's runtime walks the normal finalize chain: stop, capture, destroy, release.
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
    }

    @Test
    void nonQoderCoresNeverParkEvenOnQuestionShapedOutput() {
        binding.setCoreId("opencode");
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Should I proceed?", usage(), false)));

        CoreResult result = service.execute(opencodeSpec(), opencodeAdapter, task());

        assertThat(result.finalOutput()).isEqualTo("Should I proceed?");
        // One prompt, no park, no waiting events: other cores keep today's single-turn behavior.
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        assertThat(published(RunWaitingForInputEvent.class)).isEmpty();
        assertThat(published(TurnCompletedEvent.class)).isEmpty();
    }

    @Test
    void aFollowUpTurnGetsAFreshPerTurnDeadlineWindowFromTheAttemptFrozenPolicy() throws Exception {
        // The follow-up turn's window is armed from the policy frozen when the
        // attempt started (60 minutes here), not from the run's frozen deadline
        // (45 minutes past the fixed clock's now) and never from a second live
        // read: a System Config change while the run is parked must not move it.
        int[] reads = {0};
        TaskDeadlineProperties turnDeadlines = new TaskDeadlineProperties() {
            @Override
            public Duration deadline() {
                return Duration.ofMinutes(++reads[0] == 1 ? 60 : 1);
            }
        };
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler(CLOCK);
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, turnDeadlines);
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "[NEED-INPUT] q?", usage(), false),
                new CoreResult("session-1", "done", usage(), false)));
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !published(RunWaitingForInputEvent.class).isEmpty());
            coordinator.submitAnswer(RUN, "yes");
        });
        answerer.start();
        service.execute(spec(), adapter, task());
        answerer.join();

        assertThat(reads[0]).as("the attempt freezes the policy once, at its start").isEqualTo(1);
        assertThat(scheduler.scheduledInstants()).containsExactly(
                NOW.plus(Duration.ofMinutes(45)),
                NOW.plus(Duration.ofMinutes(60)));
    }

    /**
     * The service wired with the migration collaborators (fleet runtime
     * governance 4.4) and a clock-aligned scheduler; both production adapters
     * are registered so a migrated attempt can resolve the NEW core's adapter.
     */
    private CoreExecutionService migrationAwareService(ManualDeadlineScheduler scheduler,
            TaskDeadlineProperties turnDeadlines) {
        return new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, turnDeadlines,
                agents, trajectories, new CoreAdapters(List.of(adapter, opencodeAdapter)));
    }

    /** The agent's current runtime selection as the answer path reads it. */
    private static Agent agentWithCurrentRuntime(String coreId, ExecutionMode mode) {
        return Agent.builder().id(AGENT).name("fleet-agent")
                .adkProvider(coreId).executionMode(mode)
                .updatedAt(Instant.parse("2026-10-11T09:00:00Z")).build();
    }

    @Test
    void answeringAfterTheAgentCoreChangedMigratesTheRunToTheNewCore() throws Exception {
        // The agent's CURRENT config is opencode/HOST while the run was frozen for
        // qoder/HOST: the operator moved the fleet while this run waited for input.
        when(agents.findById(AGENT)).thenReturn(Optional.of(agentWithCurrentRuntime("opencode", ExecutionMode.HOST)));
        when(trajectories.findByRunIdOrderByTurnNumberAsc(RUN)).thenReturn(List.of(
                SessionTrajectory.builder().runId(RUN).turnNumber(1).role("user")
                        .content("please do the work").build(),
                SessionTrajectory.builder().runId(RUN).turnNumber(2).role("assistant")
                        .content("Editing the schema")
                        .toolCalls("[{\"id\":\"call-1\",\"name\":\"write_file\",\"arguments\":\"{}\"}]").build(),
                SessionTrajectory.builder().runId(RUN).turnNumber(3).role("tool")
                        .content("file written").toolCallId("call-1").build()));
        TaskDeadlineProperties turnDeadlines = new TaskDeadlineProperties();
        turnDeadlines.setDeadlineMinutes(60);
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler(CLOCK);
        service = migrationAwareService(scheduler, turnDeadlines);
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Working\n[NEED-INPUT] Which database?", usage(), false),
                new CoreResult("session-1", "All done on the new core", usage(), false)));
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !published(RunWaitingForInputEvent.class).isEmpty());
            coordinator.submitAnswer(RUN, "postgres");
        });
        answerer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        answerer.join();

        // The loop tore down the old runtime (verified stop -> capture -> destroy ->
        // release) and relaunched on the NEW core before re-prompting.
        assertThat(recordedSteps).containsExactly(
                "acquire", "prepare", "launch-profile", "launch", "open-session", "prompt",
                "stop-writers", "capture", "destroy", "release",
                "acquire", "prepare", "launch-profile", "launch", "open-session", "prompt",
                "stop-writers", "capture", "destroy", "release");
        assertThat(backend.launchCalls).isEqualTo(2);
        // The re-seed task carries the trajectory history and the answer as the user
        // prompt -- NOT the bare-answer task of the same-core path.
        assertThat(session.secondPrompt.userPrompt()).isEqualTo("postgres");
        assertThat(session.secondPrompt.systemPrompt()).isBlank();
        assertThat(session.secondPrompt.history()).containsExactly(
                LlmMessage.user("please do the work"),
                LlmMessage.assistant("Editing the schema",
                        List.of(new LlmToolCall("call-1", "write_file", "{}"))),
                LlmMessage.tool("file written", "call-1"));
        assertThat(result.finalOutput()).isEqualTo("All done on the new core");
        assertThat(result.turnAlreadyAccounted()).isFalse();
        // A superseding binding is recorded: the new core, its credential reference,
        // the agent's current configuration revision and a fresh frozen deadline.
        assertThat(binding.getCoreId()).isEqualTo("opencode");
        assertThat(binding.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(binding.getCredentialRef()).isNull();
        assertThat(binding.getConfigurationRevision())
                .isEqualTo(AGENT + "@" + Instant.parse("2026-10-11T09:00:00Z"));
        assertThat(binding.getDeadline())
                .isEqualTo(NOW.plus(turnDeadlines.deadline()).truncatedTo(ChronoUnit.SECONDS));
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
        // The run stayed observable end to end: waiting, question turn, answer.
        assertThat(published(RunWaitingForInputEvent.class)).isNotEmpty();
        assertThat(published(TurnCompletedEvent.class)).isNotEmpty();
        assertThat(published(RunInputReceivedEvent.class)).isNotEmpty();
        // The follow-up turn ran under a fresh per-turn window, not the frozen deadline.
        assertThat(scheduler.scheduledInstants()).hasSize(2);
        assertThat(scheduler.scheduledInstants().get(1)).isAfter(scheduler.scheduledInstants().get(0));
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
    }

    @Test
    void aCoreRoundTripStillMigratesOnTheSecondAnswer() throws Exception {
        // qoder/HOST frozen -> answer 1 with the agent moved to qoder/SANDBOX
        // (migrate; the SANDBOX twin backend is registered below) -> the agent
        // moves BACK to qoder/HOST -> turn 2 ends with a question again -> answer
        // 2 with the agent on qoder/HOST. The old dispatch-snapshot comparison
        // saw spec(qoder/HOST)==agent(qoder/HOST) and took the same-session
        // branch, leaving the run parked on a placement its agent no longer
        // declares. The live selection (`current`) is what the comparison must
        // track. Both pairs stay qoder so the parking gate keeps firing (parking
        // is a qoder-core behavior).
        RecordingBackend sandboxTwin = new RecordingBackend();
        sandboxTwin.declaredMode = ExecutionMode.SANDBOX;
        when(agents.findById(AGENT)).thenReturn(
                Optional.of(agentWithCurrentRuntime("qoder", ExecutionMode.SANDBOX)),
                Optional.of(agentWithCurrentRuntime("qoder", ExecutionMode.HOST)));
        when(trajectories.findByRunIdOrderByTurnNumberAsc(RUN)).thenReturn(List.of(
                SessionTrajectory.builder().runId(RUN).turnNumber(1).role("user").content("seed").build()));
        TaskDeadlineProperties turnDeadlines = new TaskDeadlineProperties();
        turnDeadlines.setDeadlineMinutes(60);
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler(CLOCK);
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend, sandboxTwin)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler, coordinator, eventPublisher, turnDeadlines,
                agents, trajectories, new CoreAdapters(List.of(adapter)));
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Working\n[NEED-INPUT] q1?", usage(), false),
                new CoreResult("session-1", "Still stuck\n[NEED-INPUT] q2?", usage(), false),
                new CoreResult("session-1", "All done", usage(), false)));
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> published(RunWaitingForInputEvent.class).size() >= 1);
            coordinator.submitAnswer(RUN, "answer-1");
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> published(RunWaitingForInputEvent.class).size() >= 2);
            coordinator.submitAnswer(RUN, "answer-2");
        });
        answerer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        answerer.join();

        // Three launches (qoder/HOST -> qoder/SANDBOX -> qoder/HOST): the middle
        // one lands on the SANDBOX twin, the outer two on the HOST backend.
        assertThat(backend.launchCalls).isEqualTo(2);
        assertThat(sandboxTwin.launchCalls).isEqualTo(1);
        assertThat(recordedSteps).containsSubsequence(
                "launch", "open-session", "prompt",
                "stop-writers", "capture", "destroy", "release",
                "launch", "open-session", "prompt",
                "stop-writers", "capture", "destroy", "release",
                "launch", "open-session", "prompt");
        // The binding ends on the restored qoder/HOST selection.
        assertThat(binding.getCoreId()).isEqualTo("qoder");
        assertThat(binding.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        // The third prompt is the re-seeded task: the answer as user prompt over
        // the durable trajectory history -- not the bare-answer task.
        assertThat(session.thirdPrompt.userPrompt()).isEqualTo("answer-2");
        assertThat(session.thirdPrompt.history()).isNotEmpty();
        assertThat(result.finalOutput()).isEqualTo("All done");
        assertThat(result.turnAlreadyAccounted()).isFalse();
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
    }

    @Test
    void answeringWithAnUnchangedCoreKeepsTheSameSessionBehavior() throws Exception {
        // The agent store is wired and the agent's CURRENT config still agrees with
        // the frozen spec (qoder/HOST): the answer path must stay byte-for-byte the
        // same-core continuation -- bare answer, no history, no teardown between
        // turns, and the trajectory store is never consulted.
        when(agents.findById(AGENT)).thenReturn(Optional.of(agentWithCurrentRuntime("qoder", ExecutionMode.HOST)));
        TaskDeadlineProperties turnDeadlines = new TaskDeadlineProperties();
        turnDeadlines.setDeadlineMinutes(60);
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler(CLOCK);
        service = migrationAwareService(scheduler, turnDeadlines);
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Working\n[NEED-INPUT] Which database?", usage(), false),
                new CoreResult("session-1", "All done", usage(), false)));
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !published(RunWaitingForInputEvent.class).isEmpty());
            coordinator.submitAnswer(RUN, "postgres");
        });
        answerer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        answerer.join();

        assertThat(result.finalOutput()).isEqualTo("All done");
        assertThat(result.turnAlreadyAccounted()).isFalse();
        // One launch, one session, TWO prompts -- same session, no re-open, no finalize in between.
        assertThat(backend.launchCalls).isEqualTo(1);
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "prompt", "stop-writers", "capture", "destroy", "release");
        // The follow-up task is the bare answer: session context, no re-sent system/history.
        assertThat(session.secondPrompt.userPrompt()).isEqualTo("postgres");
        assertThat(session.secondPrompt.systemPrompt()).isBlank();
        assertThat(session.secondPrompt.history()).isEmpty();
        // The frozen binding was never rewritten by the unchanged answer path.
        assertThat(binding.getCoreId()).isEqualTo("qoder");
        assertThat(binding.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(binding.getCredentialRef()).isEqualTo("qoder:operator");
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
        verifyNoInteractions(trajectories);
    }

    // ------------------------------------------------------------------ recovery

    private RuntimeRecoveryCoordinator recovery() {
        return new RuntimeRecoveryCoordinator(new ExecutionBackendRegistry(List.of(backend)), runtimes);
    }

    @Test
    void recoveryReapsOnlyARunOwnedRuntimeWithRunStoreEvidence() {
        RunExecutionBinding evidence = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .runtimeEnvironmentId("env-1").runtimeOwnershipIdentity("run-owner-identity")
                .runtimeEndpoint("http://127.0.0.1:9311/").workspaceKind(WorkspaceKind.DIRECT)
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision decision = recovery().reconcile(evidence);

        assertThat(decision.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.REAPED);
        assertThat(decision.reason()).isEqualTo("Destroyed the run-owned runtime of run " + RUN
                + "; its Direct workspace lease stays held (recovery holds no verified stop proof)");
        assertThat(recordedSteps).containsExactly("destroy");
    }

    @Test
    void recoveryHoldsAnUnresolvedRecordInsteadOfActing() {
        RunExecutionBinding noOwnership = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision held = recovery().reconcile(noOwnership);

        assertThat(held.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.HELD);
        assertThat(held.reason()).isEqualTo("Run store records no verifiable ownership identity for run " + RUN
                + "; nothing is adopted or reaped and the workspace lease stays held");
        RuntimeRecoveryCoordinator.RecoveryDecision noEvidence = recovery().reconcile(null);
        assertThat(noEvidence).isEqualTo(new RuntimeRecoveryCoordinator.RecoveryDecision(
                RuntimeRecoveryCoordinator.Decision.HELD,
                "No run-store evidence for a reconstructed record; nothing is adopted or reaped"));
        assertThat(recordedSteps).isEmpty();
    }

    @Test
    void recoveryAdoptsAnAlreadyLiveRuntimeOfTheSameRunOwnership() {
        runOwnedInProcess();
        RunExecutionBinding evidence = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .runtimeEnvironmentId("env-1").runtimeOwnershipIdentity("run-owner-identity")
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision decision = recovery().reconcile(evidence);

        assertThat(decision.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.ADOPTED);
        assertThat(decision.reason()).isEqualTo("Adopted the live run-owned runtime of run " + RUN
                + " (ownership identity run-owner-identity verified against the run store)");
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session");
        assertThat(runtimes.activeRuns(AGENT)).containsExactly(RUN);
    }

    @Test
    void recoveryHoldsARecordWhoseModeHasNoRegisteredBackendInsteadOfAborting() {
        // The sweep must hold (fail closed), like the null-mode case, instead of throwing
        // from the backend lookup and aborting every remaining record.
        RunExecutionBinding sandboxEvidence = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.SANDBOX)
                .runtimeEnvironmentId("env-1").runtimeOwnershipIdentity("run-owner-identity")
                .runtimeEndpoint("http://127.0.0.1:9311/").workspaceKind(WorkspaceKind.DIRECT)
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision held = recovery().reconcile(sandboxEvidence);

        assertThat(held.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.HELD);
        assertThat(held.reason()).isEqualTo("Run store records execution mode SANDBOX for run " + RUN
                + ", but this process has no registered backend for that mode; nothing is adopted or reaped"
                + " and the workspace lease stays held");
        assertThat(recordedSteps).isEmpty();
    }
}
