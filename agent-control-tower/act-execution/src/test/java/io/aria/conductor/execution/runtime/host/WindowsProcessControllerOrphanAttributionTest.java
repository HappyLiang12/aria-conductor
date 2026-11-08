package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * F25, high: a direct run cancel failed the stop proof when the core left a
 * job-member child whose intermediate parent died between two 500 ms tree
 * samples. Live evidence (run 4bd06a41): {@code root=gone known=[...]
 * job=[35224@134386018254682214] unaccounted=[...] ... fails closed}; the child
 * (sleep.exe spawned 12.3 s after the root, parent already gone) lived on while
 * the run ended FAILED. The proof was correctly fail-closed; the defect was
 * attribution. A single-supervised-run orphan created at or after the run's root
 * is now attributable to the run and stopped by identity.
 *
 * <p>The real-process fixture reproduces exactly that shape on the OS, without
 * mocks: the root is a {@code cmd.exe} that spawns a descendant and exits after
 * ~1 s; the descendant waits until the root is gone, then starts a long-lived
 * {@code ping.exe} and exits itself. The sampler can never attribute the ping:
 * its first pass (500 ms) sees the root and the descendant but no ping yet, and
 * every later pass skips the run because the root's identity no longer matches.
 * At proof time the root tree is therefore empty, the ping is reachable only
 * through the shared job's inventory, and only the F25 attribution can account
 * for it. That ordering is what makes the shape deterministic; the only
 * OS-dependent precondition is that the run's root was really admitted to the
 * job ({@code job-backstop=assign:ok}), which the test asserts explicitly.
 */
@Timeout(240)
class WindowsProcessControllerOrphanAttributionTest {

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

    /**
     * The live F25 shape on the real OS: the root is already gone, the surviving
     * writer is a job member created after it, and the intermediate parent that
     * created the writer has died as well. The controller supervises exactly one
     * run, so the shared job's membership is unambiguous and the orphan must be
     * attributed, killed by identity and verdict-checked -- never left as an
     * unaccounted member that fails the proof closed.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void anOrphanedJobMemberOfTheOnlySupervisedRunIsStoppedByIdentity() throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        assertThat(systemRoot).as("a Windows host always exports SystemRoot").isNotBlank();
        Path cmd = Path.of(systemRoot, "System32", "cmd.exe");
        Path ping = Path.of(systemRoot, "System32", "PING.EXE");
        assertThat(cmd).as("the fixture root is the OS cmd.exe").isRegularFile();
        assertThat(ping).as("the fixture child is the OS ping.exe").isRegularFile();
        String pingPath = ping.toString();

        Path working = Files.createTempDirectory("host-orphaned-job-member");
        workingDirectories.add(working);
        // Unique executable names are the discriminator: the OS image name is readable for
        // another process through ProcessHandle.info().command() (a command line is not),
        // so the fixture's own two short pings can never be mistaken for the long-lived orphan.
        Path descendant = working.resolve("f25-descendant.exe");
        Path orphan = working.resolve("f25-orphan.exe");
        Files.copy(cmd, descendant);
        Files.copy(ping, orphan);
        // The root spawns its only descendant, then exits after ~1 s -- the shared job
        // inventory is then the only thing that can still see the descendant's child.
        // The first wait keeps the fixture honest: the root's job membership is requested
        // right after the OS returned it, so a descendant created earlier would predate
        // the membership and never be a job member (the documented best-effort assign,
        // not the F25 shape).
        Files.writeString(working.resolve("root.bat"), String.join("\r\n",
                "@echo off",
                "\"" + pingPath + "\" -n 2 127.0.0.1 >nul",
                "start /b \"\" \"%~dp0f25-descendant.exe\" /d /c \"%~dp0intermediate.bat\"",
                "\"" + pingPath + "\" -n 2 127.0.0.1 >nul",
                ""));
        // The descendant outlives the root by several seconds, spawns the long-lived
        // child only once the root is gone (so no tree sample can reach it), and then
        // exits itself: the child ends up with a dead parent as well, the shape the
        // live runs had, while the root tree is already unenumerable at proof time.
        Files.writeString(working.resolve("intermediate.bat"), String.join("\r\n",
                "@echo off",
                "\"" + pingPath + "\" -n 5 127.0.0.1 >nul",
                "start /b \"\" \"%~dp0f25-orphan.exe\" -n 120 127.0.0.1",
                "\"" + pingPath + "\" -n 2 127.0.0.1 >nul",
                ""));

        WindowsProcessController controller = new WindowsProcessController();
        // The profile's environment is cleared by the launcher; cmd needs its own
        // root and ping is referenced by absolute path, so nothing else is required.
        LaunchProfile profile = new LaunchProfile(
                List.of(cmd.toString(), "/d", "/s", "/c", "root.bat"),
                Map.of("SystemRoot", systemRoot, "windir", systemRoot), working.toString());
        OwnedProcess owned = controller.start(UUID.randomUUID(), profile);
        Process root = owned.liveProcess();
        cleanups.add(() -> {
            root.destroyForcibly();
            root.waitFor(10, TimeUnit.SECONDS);
            controller.release(owned);
            controller.close();
        });
        assumeTrue(owned.technique().contains("job-backstop=assign:ok"),
                "the host JVM already holds the launched root inside another job object, so the run's"
                        + " shared job never sees it and its inventory cannot be exercised: "
                        + owned.technique());

        long descendantPid = awaitChildRunning(root.pid(), "f25-descendant.exe", 15_000);
        long childPid = awaitChildRunning(descendantPid, "f25-orphan.exe", 30_000);
        cleanups.add(() -> ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly));
        cleanups.add(() -> ProcessHandle.of(descendantPid).ifPresent(ProcessHandle::destroyForcibly));

        assertThat(root.waitFor(30, TimeUnit.SECONDS))
                .as("the fixture root must exit on its own: the orphan is unreachable from a tree walk")
                .isTrue();
        assertThat(isAlive(childPid))
                .as("the fixture child must still be alive after its root and its parent are gone")
                .isTrue();
        // Non-vacuity of the F25 shape: the sampler never remembered the orphan, so the tree
        // and the sampler cannot account for it and the job inventory is its only attribution.
        String childRecord = controller.supervisorCommand("identity " + childPid);
        assertThat(childRecord)
                .as("the orphan's OS identity must be readable for the kill-by-identity assertion")
                .startsWith(childPid + "@");
        assertThat(controller.observedRecordContains(owned.runId(), childRecord))
                .as("the sampler must never have seen the orphan, or the job attribution is not what"
                        + " stopped it")
                .isFalse();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        assertThat(proof.allWritersStopped())
                .as("a job member created after the only supervised run's root is attributable to that"
                        + " run: it must be killed by identity and verdict-checked, not fail the proof"
                        + " closed as an unaccounted shared-job member")
                .isTrue();
        assertThat(isAlive(childPid))
                .as("the attributed orphan must be gone, not merely unaccounted for")
                .isFalse();
    }

    // ------------------------------------------------------------------ decision helper

    /** A real-run root creation identity: FILETIME ticks (100 ns since 1601). */
    private static final long ROOT_TICKS = 134_386_018_254_682_214L;

    @Test
    void aMemberCreatedAfterTheOnlySupervisedRunsRootIsAttributable() {
        String member = "35224@" + (ROOT_TICKS + 1_230_000);

        assertThat(WindowsProcessController.attributableJobMembers(List.of(member), ROOT_TICKS, 1))
                .containsExactly(member);
    }

    @Test
    void aMemberCreatedAtTheRootsCreationTicksIsAttributable() {
        String member = "35224@" + ROOT_TICKS;

        assertThat(WindowsProcessController.attributableJobMembers(List.of(member), ROOT_TICKS, 1))
                .containsExactly(member);
    }

    @Test
    void aMemberCreatedBeforeTheRunsRootIsNeverAttributable() {
        String older = "28540@" + (ROOT_TICKS - 1);

        assertThat(WindowsProcessController.attributableJobMembers(List.of(older), ROOT_TICKS, 1))
                .as("a member older than this run's root cannot be its writer")
                .isEmpty();
    }

    @Test
    void aJobSharedByMoreThanOneSupervisedRunAttributesNothing() {
        String member = "35224@" + (ROOT_TICKS + 1_230_000);

        assertThat(WindowsProcessController.attributableJobMembers(List.of(member), ROOT_TICKS, 2))
                .as("a concurrent run's writer is indistinguishable from this run's orphan")
                .isEmpty();
        assertThat(WindowsProcessController.attributableJobMembers(List.of(member), ROOT_TICKS, 0))
                .as("no supervised run has no attribution evidence at all")
                .isEmpty();
    }

    @Test
    void anUnverifiableOrMalformedMemberIsNeverAttributable() {
        List<String> members = List.of(
                "unverifiable:35224",
                "unverifiable:35224@" + (ROOT_TICKS + 1),
                "35224",
                "35224@",
                "@" + (ROOT_TICKS + 1),
                "35224@not-ticks",
                "35224@" + (ROOT_TICKS + 1) + "@7",
                "");

        assertThat(WindowsProcessController.attributableJobMembers(members, ROOT_TICKS, 1))
                .as("an unreadable identity is not absence, and a malformed record names no identity")
                .isEmpty();
    }

    @Test
    void onlyTheEligibleMembersOfAPopulationAreAttributed() {
        String after = "35224@" + (ROOT_TICKS + 5);
        String alsoAfter = "35928@" + (ROOT_TICKS + 9);
        List<String> members = List.of(after, "28540@" + (ROOT_TICKS - 5), "unverifiable:34660",
                "34660@bad", alsoAfter);

        assertThat(WindowsProcessController.attributableJobMembers(members, ROOT_TICKS, 1))
                .containsExactly(after, alsoAfter);
    }

    // ------------------------------------------------------------------ harness

    /** Bounded poll for the first direct child of {@code parentPid} whose OS image name matches. */
    private static long awaitChildRunning(long parentPid, String commandFragment, long timeoutMillis)
            throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        List<String> children = List.of();
        while (Instant.now().isBefore(limit)) {
            Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
            if (parent.isPresent()) {
                children = parent.get().children()
                        .map(handle -> handle.pid() + "=" + handle.info().command().orElse("?"))
                        .toList();
                Optional<ProcessHandle> child = parent.get().children()
                        .filter(handle -> handle.info().command().orElse("").toLowerCase(Locale.ROOT)
                                .contains(commandFragment))
                        .findFirst();
                if (child.isPresent()) {
                    return child.get().pid();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no child of pid " + parentPid + " running '" + commandFragment
                + "' appeared within " + timeoutMillis + " ms; observed children: " + children);
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
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
            } catch (Exception e) {
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
