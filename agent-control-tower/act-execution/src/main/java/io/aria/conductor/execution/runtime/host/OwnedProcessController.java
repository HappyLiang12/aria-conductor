package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * OS-level ownership control of a Host run's runtime (spec 5.1 step 6, 5.3):
 * launch with an explicit argv, attach ownership the moment the core was created
 * -- the platform's supervision channel (and the Windows job object) exists
 * before the launch, and the residual CreateProcess-to-attach window is covered
 * by the identity-validated enumerated sweep, never by a claim -- and then
 * pause/resume/stop exactly the owned process records -- never a process name,
 * never a PID on trust.
 *
 * <p>Every mutating operation re-verifies the durable {@link OwnedProcess}
 * identity through the OS first, and only for a record this controller bound to
 * a launch of its own (matching the run ownership nonce, the pid and the
 * creation identity of the in-memory record {@link #start} returned). A record
 * reconstructed from a persisted ownership identity after a restart has no such
 * binding, so it is refused by every control path -- fail closed -- and
 * adopting or reaping a surviving run is the recovery coordinator's job, with
 * run-store evidence. A request that cannot be verified (a reused PID, a
 * foreign creation identity, an unbound record, an elapsed window, a strategy
 * that is not available on this OS) fails explicitly: no control state is
 * reported that the runtime did not actually reach. The deadline of a control
 * request is the caller's own window and is never folded into the run's
 * execution deadline.
 *
 * <p>{@link #stop} returns a {@link StopProof} whose {@code allWritersStopped}
 * is true only when every discovered owned record -- including descendants the
 * core spawned after launch -- is verified stopped; an incomplete verification
 * is reported as {@code false}, never as a proof.
 */
public interface OwnedProcessController extends AutoCloseable {

    /**
     * Launches one run-owned runtime from the trusted profile and returns its
     * durable ownership record, bound to this controller until {@link #release}.
     * The supervision channel exists before the runtime is created (on Windows
     * its script creates the run's job object first, with kill-on-close), and
     * membership is requested as soon as the OS returned the process, so a
     * descendant spawned afterwards is still covered by the enumerated sweep.
     */
    OwnedProcess start(UUID runId, LaunchProfile profile);

    /**
     * True only for a record this controller bound at {@link #start} -- run
     * ownership nonce, pid and creation identity all matching that in-memory
     * record -- whose creation identity the OS still confirms. A record
     * reconstructed from a persisted ownership identity has no binding and is
     * never owned: it is refused, and the surviving run is left untouched.
     */
    boolean owns(OwnedProcess process);

    /** Bounded termination of the owned tree; verified quiescence or an honest refusal. */
    StopProof stop(OwnedProcess process, Instant deadline);

    /** Suspends the whole owned tree; only a fully suspended tree is acknowledged as paused. */
    CompletionStage<ControlAck> pause(OwnedProcess process, Instant deadline);

    /** Resumes the same owned tree (matching every recorded suspension). */
    CompletionStage<ControlAck> resume(OwnedProcess process, Instant deadline);

    /**
     * Ends supervision of one run's records. It sends no termination command:
     * the caller stops writers first. Closing the last supervision channel
     * closes the Windows job object unless other runs are still supervised, and
     * the job's kill-on-close limit then terminates any member left alive -- the
     * OS-level backstop for a hard JVM or supervisor death, not a signal of this
     * contract.
     */
    void release(OwnedProcess process);

    /**
     * Closes the OS supervision channel. A process left alive in a Windows job
     * dies with the last handle to that job (kill-on-close); nothing else is
     * terminated by this call.
     */
    @Override
    default void close() {
    }

    /**
     * Explicit launch shared by every platform controller: an argv list, the
     * profile's working directory, an environment cleared before the profile
     * variables are applied, and a verification that the process the OS actually
     * started is the executable the trusted profile named. The platform default
     * environment and any shell interpolation are deliberately not used.
     */
    static Process launch(LaunchProfile profile) {
        List<String> argv = profile.argv();
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("LaunchProfile has an empty argv");
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(Path.of(profile.workingDirectory()).toFile());
        builder.environment().clear();
        builder.environment().putAll(profile.env());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to launch the run-owned runtime: " + e.getMessage(), e);
        }
        verifyExecutable(argv.get(0), process);
        return process;
    }

    /**
     * Authenticates the launched runtime against the trusted profile: the OS
     * reports the executable the process is running, and it must be the one the
     * profile named. An OS that cannot report it fails the launch instead of
     * supervising an unidentified process.
     */
    private static void verifyExecutable(String argv0, Process process) {
        String expected = stem(Path.of(argv0).getFileName().toString());
        String actual = process.info().command()
                .map(command -> stem(Path.of(command).getFileName().toString()))
                .orElseThrow(() -> {
                    process.destroyForcibly();
                    return new IllegalStateException("The OS did not report the executable of the launched runtime"
                            + " (pid " + process.pid() + "); refusing to supervise an unidentified process");
                });
        if (!expected.equalsIgnoreCase(actual)) {
            process.destroyForcibly();
            throw new IllegalStateException("The runtime the OS started (" + actual
                    + ") is not the executable of the trusted launch profile (" + expected + ")");
        }
    }

    /** Compares executables by name: the profile may name a command resolved through PATH. */
    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * The verified control controller for this OS. An OS with no validated
     * technique is rejected explicitly rather than silently downgrading to PID
     * signalling.
     */
    static OwnedProcessController forCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsProcessController();
        }
        if (os.contains("linux") || os.contains("mac") || os.contains("nux") || os.contains("bsd")) {
            return new PosixProcessController();
        }
        throw new UnsupportedOperationException(
                "No verified Host process-control technique for this OS: " + System.getProperty("os.name"));
    }
}
