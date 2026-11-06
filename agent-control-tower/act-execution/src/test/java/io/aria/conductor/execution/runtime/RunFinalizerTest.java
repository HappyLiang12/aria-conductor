package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.WorkspaceKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ordering and truthfulness of the one finalization owner: stop all owned
 * writers -&gt; verify stopped -&gt; export/capture -&gt; destroy environment -&gt;
 * release locks. A prompt-completion event is not a stop proof, a refused stop
 * is retried and never followed by destroy, and a proof the backend never
 * returned is never minted here.
 */
class RunFinalizerTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID OTHER_RUN = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    /** The finalizer's one injected time source: the retry bound is measured against it, not the wall clock. */
    private static final Instant ANCHOR = Instant.now();
    private static final Clock CLOCK = Clock.fixed(ANCHOR, ZoneOffset.UTC);
    private static final Instant WINDOW = ANCHOR.plus(Duration.ofMinutes(10));
    private static final Instant ELAPSED_WINDOW = ANCHOR.minus(Duration.ofSeconds(1));

    private final List<String> recordedSteps = new ArrayList<>();

    // ------------------------------------------------------------------ doubles

    private final class RecordingBackend implements ExecutionBackend {
        final List<StopProof> proofs = new ArrayList<>();
        int nextProof;
        int stopCalls;
        final List<StopProof> exportedProofs = new ArrayList<>();
        boolean destroyed;

        @Override
        public io.aria.conductor.common.runtime.ExecutionMode mode() {
            return io.aria.conductor.common.runtime.ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
            throw new UnsupportedOperationException("finalization never prepares an environment");
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
            throw new UnsupportedOperationException("finalization never launches a runtime");
        }

        @Override
        public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException("finalization never pauses writers");
        }

        @Override
        public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException("finalization never resumes writers");
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
            recordedSteps.add("stop-writers");
            stopCalls++;
            return nextProof < proofs.size() ? proofs.get(nextProof++) : new StopProof(handle.runId(), false);
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            exportedProofs.add(proof);
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
            throw new UnsupportedOperationException("finalization never acquires a lease");
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

    private static RuntimeHandle handle() {
        return new RuntimeHandle(RUN, io.aria.conductor.common.runtime.ExecutionMode.HOST,
                "run-owned-environment", "run-owner-identity", java.net.URI.create("http://127.0.0.1:9311/"));
    }

    private static WorkspaceLease lease() {
        return new WorkspaceLease(UUID.fromString("00000000-0000-0000-0000-0000000000f3"), RUN, WorkspaceKind.SCRATCH,
                Path.of("scratch", RUN.toString()), null, "runtime-root", null);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void finishOrdersStopCaptureDestroyRelease() {
        RecordingBackend backend = new RecordingBackend();
        backend.proofs.add(new StopProof(RUN, true));
        RecordingWorkspace workspaces = new RecordingWorkspace();

        ArtifactBundle bundle = new RunFinalizer(workspaces, CLOCK)
                .finish(backend, handle(), lease(), WINDOW);

        assertThat(recordedSteps).containsExactly("stop-writers", "capture", "destroy", "release");
        assertThat(bundle).isSameAs(workspaces.bundle);
        assertThat(backend.stopCalls).isEqualTo(1);
        assertThat(backend.exportedProofs).isEmpty(); // the workspace service owns export
    }

    @Test
    void aPromptCompletionIsNotAStopProofSoARefusedStopIsNeverFinalized() {
        // The run's prompt has settled (the caller has its CoreResult) but the backend
        // refuses the stop: nothing may be captured, destroyed or released.
        RecordingBackend backend = new RecordingBackend();
        backend.proofs.add(new StopProof(RUN, false));
        RecordingWorkspace workspaces = new RecordingWorkspace();

        assertThatThrownBy(() -> new RunFinalizer(workspaces, CLOCK).finish(backend, handle(), lease(), WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was not finalized: the backend did not verify that all writers"
                        + " stopped (StopProof[runId=" + RUN + ", allWritersStopped=false]);"
                        + " nothing was captured, destroyed or released");

        assertThat(recordedSteps).containsExactly("stop-writers", "stop-writers"); // retried before giving up
        assertThat(backend.destroyed).isFalse();
        assertThat(workspaces.capturedProof).isNull();
        assertThat(workspaces.releasedProof).isNull();
    }

    @Test
    void aRetriedStopThatVerifiesIsFinalizedWithTheBackendsOwnProof() {
        RecordingBackend backend = new RecordingBackend();
        StopProof refused = new StopProof(RUN, false);
        StopProof verified = new StopProof(RUN, true);
        backend.proofs.add(refused);
        backend.proofs.add(verified);
        RecordingWorkspace workspaces = new RecordingWorkspace();

        ArtifactBundle bundle = new RunFinalizer(workspaces, CLOCK).finish(backend, handle(), lease(), WINDOW);

        assertThat(recordedSteps)
                .containsExactly("stop-writers", "stop-writers", "capture", "destroy", "release");
        assertThat(bundle.complete()).isTrue();
        // Exactly the proof the backend returned is carried into capture and release -- never minted.
        assertThat(workspaces.capturedProof).isSameAs(verified);
        assertThat(workspaces.releasedProof).isSameAs(verified);
    }

    @Test
    void aForeignStopProofIsRefusedAndNothingIsDestroyed() {
        RecordingBackend backend = new RecordingBackend();
        backend.proofs.add(new StopProof(OTHER_RUN, true));
        RecordingWorkspace workspaces = new RecordingWorkspace();

        assertThatThrownBy(() -> new RunFinalizer(workspaces, CLOCK).finish(backend, handle(), lease(), WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stop proof run " + OTHER_RUN + " does not match the finalized run " + RUN
                        + "; nothing was captured, destroyed or released");

        // A foreign proof is a hard refusal, not a retryable refusal: exactly one stop was attempted.
        assertThat(recordedSteps).containsExactly("stop-writers");
        assertThat(backend.destroyed).isFalse();
        assertThat(workspaces.capturedProof).isNull();
    }

    @Test
    void aMissingProofIsNeverMintedAndNothingIsFinalized() {
        RecordingBackend backend = new RecordingBackend();
        backend.proofs.add(null);
        RecordingWorkspace workspaces = new RecordingWorkspace();

        assertThatThrownBy(() -> new RunFinalizer(workspaces, CLOCK).finish(backend, handle(), lease(), WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The backend returned no stop proof for run " + RUN
                        + "; nothing was captured, destroyed or released");

        assertThat(recordedSteps).containsExactly("stop-writers");
        assertThat(backend.destroyed).isFalse();
    }

    @Test
    void anElapsedCleanupWindowIsRefusedWithoutARetry() {
        RecordingBackend backend = new RecordingBackend();
        backend.proofs.add(new StopProof(RUN, false));
        RecordingWorkspace workspaces = new RecordingWorkspace();

        assertThatThrownBy(() -> new RunFinalizer(workspaces, CLOCK).finish(backend, handle(), lease(), ELAPSED_WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Run " + RUN + " was not finalized:");

        // An elapsed window cannot be retried into a verified control state.
        assertThat(backend.stopCalls).isEqualTo(1);
        assertThat(recordedSteps).containsExactly("stop-writers");
        assertThat(backend.destroyed).isFalse();
    }

    @Test
    void theRetryWindowIsMeasuredAgainstTheInjectedClockNotTheWallClock() {
        // The window is already past for the wall clock but still open for the
        // injected clock: the retry bound must follow the finalizer's one time source.
        Clock behindWall = Clock.fixed(ANCHOR.minus(Duration.ofHours(1)), ZoneOffset.UTC);
        Instant window = ANCHOR.minus(Duration.ofMinutes(30));
        RecordingBackend backend = new RecordingBackend();
        backend.proofs.add(new StopProof(RUN, false));
        backend.proofs.add(new StopProof(RUN, true));
        RecordingWorkspace workspaces = new RecordingWorkspace();

        ArtifactBundle bundle = new RunFinalizer(workspaces, behindWall)
                .finish(backend, handle(), lease(), window);

        assertThat(recordedSteps)
                .containsExactly("stop-writers", "stop-writers", "capture", "destroy", "release");
        assertThat(backend.stopCalls).isEqualTo(2);
        assertThat(bundle.complete()).isTrue();
    }
}
