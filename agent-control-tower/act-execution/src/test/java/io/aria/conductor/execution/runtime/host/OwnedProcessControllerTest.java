package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage of the ownership-control contract on the current platform's
 * controller (Task 9 review round 1 restored it): every control action
 * re-verifies the run's ownership nonce together with the recorded pid and
 * creation identity, an unverifiable or expired request is refused without any
 * OS action, ending supervision runs no termination command of its own (the
 * Windows kill-on-close job backstop fires with the channel close and is
 * observed as such), and an OS with no validated technique is rejected instead
 * of PID signalling.
 *
 * <p>The launched runtime is a real, long-lived platform process ({@code ping}
 * on Windows, {@code sleep} on POSIX) -- no mock replaces a process state, and
 * the unit tier gains no new dependency on Node or PowerShell content.
 */
@Timeout(180)
class OwnedProcessControllerTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private final List<Path> workingDirectories = new ArrayList<>();
    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception ignored) {
                // best-effort cleanup of a failed test
            }
        }
        cleanups.clear();
        for (Path directory : workingDirectories) {
            deleteTreeBestEffort(directory);
        }
        workingDirectories.clear();
    }

    @Test
    void aForeignOwnershipNonceIsRefusedWithoutTouchingTheProcess() throws Exception {
        OwnedProcessController controller = OwnedProcessController.forCurrentPlatform();
        OwnedProcess owned = start(controller);

        OwnedProcess foreign = new OwnedProcess(owned.runId(), UUID.randomUUID().toString(),
                owned.rootPid(), owned.rootCreationIdentity(), owned.supervisorIdentity(), owned.technique(), null);
        assertThat(controller.owns(foreign))
                .as("a record that does not carry this run's ownership nonce must never pass").isFalse();
        assertThat(controller.stop(foreign, Instant.now().plus(DEADLINE)).allWritersStopped()).isFalse();

        ControlAck paused = controller.pause(foreign, Instant.now().plus(DEADLINE))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(paused.verified()).isFalse();
        assertThat(paused.state()).isEqualTo(ControlState.RUNNING);

        assertThat(owned.liveProcess().isAlive())
                .as("a refused control action must never touch the process").isTrue();
        assertThat(controller.owns(owned)).as("the genuine record still re-verifies").isTrue();
    }

    /**
     * Fix round 2b: the ownership nonce is verified against the launch the
     * controller performed, and that binding lives in memory only. A record
     * reconstructed from the persisted ownership identity (exactly the shape a
     * backend sees after a restart) therefore carries no binding and must be
     * refused by every control path -- fail closed -- while the surviving run is
     * left untouched: adopting or reaping it belongs to the recovery coordinator
     * with run-store evidence, never to a durable record plus an OS look-up.
     */
    @Test
    void aRecordReconstructedFromThePersistedIdentityIsRefusedWithoutABinding() throws Exception {
        OwnedProcessController launching = OwnedProcessController.forCurrentPlatform();
        OwnedProcess owned = start(launching);

        OwnedProcess reconstructed = OwnedProcess.parse(owned.ownershipIdentity());
        assertThat(reconstructed.liveProcess()).as("a reconstructed record carries no live handle").isNull();
        assertThat(launching.owns(owned))
                .as("the launching controller's own binding still re-verifies").isTrue();

        OwnedProcessController restarted = OwnedProcessController.forCurrentPlatform();
        cleanups.add(restarted::close);
        assertThat(restarted.owns(reconstructed))
                .as("a record with no in-memory binding must never pass ownership after a restart").isFalse();

        StopProof proof = restarted.stop(reconstructed, Instant.now().plus(DEADLINE));
        assertThat(proof.runId()).isEqualTo(owned.runId());
        assertThat(proof.allWritersStopped())
                .as("no stop proof may be claimed from a record the restarted controller never bound").isFalse();

        ControlAck paused = restarted.pause(reconstructed, Instant.now().plus(DEADLINE))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(paused.verified()).isFalse();
        assertThat(paused.state()).isEqualTo(ControlState.RUNNING);

        ControlAck resumed = restarted.resume(reconstructed, Instant.now().plus(DEADLINE))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(resumed.verified()).isFalse();
        assertThat(resumed.state()).as("no verified state change may be reported").isEqualTo(ControlState.PAUSED);

        assertThat(owned.liveProcess().isAlive())
                .as("a refused control action must never touch the surviving run").isTrue();
        assertThat(launching.owns(owned))
                .as("the refusal was the missing binding, not the record's identity").isTrue();
    }

    @Test
    void aStaleCreationIdentityIsRefusedWithoutTouchingTheProcess() throws Exception {
        OwnedProcessController controller = OwnedProcessController.forCurrentPlatform();
        OwnedProcess owned = start(controller);

        OwnedProcess sameRun = new OwnedProcess(owned.runId(), owned.ownershipNonce(), owned.rootPid(),
                "1", owned.supervisorIdentity(), owned.technique(), null);
        assertThat(controller.owns(sameRun))
                .as("a creation identity this run never recorded is refused by the in-memory binding").isFalse();

        OwnedProcess foreignRun = new OwnedProcess(UUID.randomUUID(), UUID.randomUUID().toString(),
                owned.rootPid(), "1", owned.supervisorIdentity(), owned.technique(), null);
        assertThat(controller.owns(foreignRun))
                .as("a live pid with a foreign creation identity must be re-verified through the OS").isFalse();
        assertThat(controller.stop(foreignRun, Instant.now().plus(DEADLINE)).allWritersStopped()).isFalse();
        assertThat(owned.liveProcess().isAlive())
                .as("a refused stop must never touch an unrelated process").isTrue();
    }

    @Test
    void anElapsedWindowIsRefusedWithoutTouchingTheOwnedTree() throws Exception {
        OwnedProcessController controller = OwnedProcessController.forCurrentPlatform();
        OwnedProcess owned = start(controller);
        Instant elapsed = Instant.now().minusSeconds(5);

        StopProof proof = controller.stop(owned, elapsed);
        assertThat(proof.runId()).isEqualTo(owned.runId());
        assertThat(proof.allWritersStopped()).as("an elapsed window cannot support a stop proof").isFalse();

        ControlAck paused = controller.pause(owned, elapsed).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(paused.verified()).isFalse();
        assertThat(paused.state()).isEqualTo(ControlState.RUNNING);

        ControlAck resumed = controller.resume(owned, elapsed).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(resumed.verified()).isFalse();
        assertThat(resumed.state()).as("no verified state change may be reported").isEqualTo(ControlState.PAUSED);

        assertThat(owned.liveProcess().isAlive())
                .as("an expired request is refused without touching the owned runtime").isTrue();
        assertThat(controller.owns(owned)).as("the refusal was the window, not the record").isTrue();
    }

    /**
     * Fix round 2a: ending supervision never runs a termination command of the
     * controller's own. On Windows the run's job is created with kill-on-close
     * (Task 1's validated technique), so a process that really is a job member
     * terminates when the last supervision channel closes: that is the OS job's
     * backstop for a hard JVM death, and this test observes it as the member's
     * death -- never as a signal. A refused assignment has no such backstop and
     * the process must survive, exactly as on POSIX.
     */
    @Test
    void releaseAndCloseEndSupervisionWithoutRunningATerminationCommand() throws Exception {
        OwnedProcessController controller = OwnedProcessController.forCurrentPlatform();
        OwnedProcess owned = start(controller);
        Process live = owned.liveProcess();
        boolean jobMember = owned.technique().contains("job-backstop=assign:ok");

        controller.release(owned);
        if (jobMember) {
            assertThat(live.waitFor(30, TimeUnit.SECONDS))
                    .as("the kill-on-close job must take the released run's member down with the channel")
                    .isTrue();
        } else {
            assertThat(live.isAlive())
                    .as("release ends supervision; it never terminates a process").isTrue();
        }

        controller.close();
        if (!jobMember) {
            assertThat(live.isAlive())
                    .as("closing the supervision channel leaves a non-membered process untouched").isTrue();
        }
    }

    @Test
    void launchRejectsAnEmptyArgvAndAnUnusableExecutable() throws Exception {
        Path working = Files.createTempDirectory("host-owned-launch");
        workingDirectories.add(working);

        assertThatThrownBy(() -> OwnedProcessController.launch(new LaunchProfile(List.of(), Map.of(),
                working.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argv");

        Path missing = working.resolve("no-such-runtime");
        assertThatThrownBy(() -> OwnedProcessController.launch(new LaunchProfile(List.of(missing.toString()),
                Map.of(), working.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to launch");
    }

    @Test
    void anUnsupportedPlatformIsRejectedInsteadOfFallingBackToPidSignalling() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "OS/2 Warp");
            assertThatThrownBy(OwnedProcessController::forCurrentPlatform)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("No verified Host process-control technique");
        } finally {
            System.setProperty("os.name", original);
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * Starts one real sleeping runtime through the platform controller and
     * registers the cleanup that terminates it, releases supervision and closes
     * the channel -- in that order.
     */
    private OwnedProcess start(OwnedProcessController controller) throws IOException {
        Path working = Files.createTempDirectory("host-owned-unit");
        workingDirectories.add(working);
        OwnedProcess owned = controller.start(UUID.randomUUID(), sleepingProfile(working));
        Process live = owned.liveProcess();
        cleanups.add(() -> {
            live.destroyForcibly();
            live.waitFor(10, TimeUnit.SECONDS);
            controller.release(owned);
            controller.close();
        });
        return owned;
    }

    /** A real long-lived platform process: no Node or PowerShell content needed. */
    private static LaunchProfile sleepingProfile(Path workingDirectory) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> argv = os.contains("win")
                ? List.of("ping", "-n", "120", "127.0.0.1")
                : List.of("sleep", "120");
        return new LaunchProfile(argv, Map.of(), workingDirectory.toString());
    }

    private static void deleteTreeBestEffort(Path root) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (!Files.exists(root)) {
                return;
            }
            try (var stream = Files.walk(root)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
