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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * F1 (review round 2), critical: the stop proof takes exactly one job inventory,
     * attributes and kills its members, and only then decides. A member being killed
     * is still a live process until the OS reports it gone, so it can spawn a child
     * that inherits the shared job <em>after</em> that inventory. The child is not in
     * the inventory's unaccounted set, so the proof could return
     * {@code allWritersStopped=true} while a live job member keeps writing. The
     * second inventory must fail the proof closed on it.
     *
     * <p>The fixture is real: a long-lived {@code ping.exe} is the only supervised
     * root, and the test JVM spawns a second long-lived {@code ping.exe} in the
     * window after the first inventory and makes it a member of the same job through
     * the controller's own supervisor. The injected member was never attributed,
     * killed or verdict-checked, so only the repeated inventory can see it.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJobMemberThatAppearsAfterTheOnlyInventoryFailsTheProofClosed() throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        assertThat(systemRoot).as("a Windows host always exports SystemRoot").isNotBlank();
        Path ping = Path.of(systemRoot, "System32", "PING.EXE");
        assertThat(ping).as("the fixture is the OS ping.exe").isRegularFile();

        Path working = Files.createTempDirectory("host-post-inventory-member");
        workingDirectories.add(working);

        WindowsProcessController controller = new WindowsProcessController();
        LaunchProfile profile = new LaunchProfile(
                List.of(ping.toString(), "-n", "120", "127.0.0.1"),
                Map.of("SystemRoot", systemRoot, "windir", systemRoot), working.toString());
        OwnedProcess owned = controller.start(UUID.randomUUID(), profile);
        Process root = owned.liveProcess();
        AtomicReference<Process> injected = new AtomicReference<>();
        cleanups.add(() -> {
            Process member = injected.get();
            if (member != null) {
                member.destroyForcibly();
                member.waitFor(10, TimeUnit.SECONDS);
            }
            root.destroyForcibly();
            root.waitFor(10, TimeUnit.SECONDS);
            controller.release(owned);
            controller.close();
        });
        assumeTrue(owned.technique().contains("job-backstop=assign:ok"),
                "the host JVM already holds the launched root inside another job object, so the run's"
                        + " shared job never sees it and its inventory cannot be exercised: "
                        + owned.technique());

        AtomicReference<String> assignment = new AtomicReference<>();
        controller.afterAttribution = () -> {
            try {
                Process member = new ProcessBuilder(ping.toString(), "-n", "120", "127.0.0.1")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                injected.set(member);
                // The member appears in the shared job only after the first inventory: this
                // proof never attributed, killed or verdict-checked it.
                assignment.set(controller.supervisorCommand("assign " + member.pid()));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("the injected job member could not be started", e);
            }
        };

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        String membership = assignment.get();
        // The supervisor's assign line is "ok" on success (its technique label renders that as
        // "job-backstop=assign:ok"); the other two forms are accepted so the premise does not hang
        // on one protocol spelling.
        assumeTrue("ok".equals(membership) || "assign:ok".equals(membership)
                        || "ok|assign:ok".equals(membership),
                "the OS refused the injected process job membership, so the post-inventory member"
                        + " could not be exercised: " + membership);
        assertThat(injected.get()).as("the injected member must have been started").isNotNull();
        assertThat(proof.allWritersStopped())
                .as("a job member that appeared after the only inventory was never attributed, killed or"
                        + " verdict-checked, so the proof must fail closed instead of certifying it stopped")
                .isFalse();
        assertThat(isAlive(owned.rootPid()))
                .as("the enumerated root tree is still stopped by the proof")
                .isFalse();
        assertThat(isAlive(injected.get().pid()))
                .as("the proof fails closed rather than guessing: a member it never decided is left alone")
                .isTrue();
    }

    /**
     * F2 (review round 2), major: {@link WindowsProcessController#start} makes the
     * root a job member before it is registered as supervised. In that window a
     * concurrent stop sees {@code started.size()==1}, treats the live member as an
     * attributable orphan of the stopping run and kills it -- cross-run termination
     * of an in-flight launch. Attribution must count in-flight launches too, so that
     * window keeps the proof closed and the launching root alive.
     *
     * <p>The fixture holds run B in exactly that window with the controller's own
     * seam (after {@code assign}, before registration), stops run A while B is in
     * flight, and then lets B finish and stops it: B's own proof must still be true.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aStopDoesNotKillTheRootOfALaunchThatIsStillInFlight() throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        assertThat(systemRoot).as("a Windows host always exports SystemRoot").isNotBlank();
        Path ping = Path.of(systemRoot, "System32", "PING.EXE");
        assertThat(ping).as("the fixture is the OS ping.exe").isRegularFile();

        Path working = Files.createTempDirectory("host-in-flight-launch");
        workingDirectories.add(working);

        WindowsProcessController controller = new WindowsProcessController();
        LaunchProfile profile = new LaunchProfile(
                List.of(ping.toString(), "-n", "120", "127.0.0.1"),
                Map.of("SystemRoot", systemRoot, "windir", systemRoot), working.toString());

        OwnedProcess runA = controller.start(UUID.randomUUID(), profile);
        Process rootA = runA.liveProcess();
        cleanups.add(() -> {
            rootA.destroyForcibly();
            rootA.waitFor(10, TimeUnit.SECONDS);
            controller.release(runA);
            controller.close();
        });
        assumeTrue(runA.technique().contains("job-backstop=assign:ok"),
                "the host JVM already holds the launched root inside another job object: "
                        + runA.technique());

        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> runBRootPid = new AtomicReference<>();
        AtomicReference<OwnedProcess> runB = new AtomicReference<>();
        AtomicReference<Throwable> runBFailure = new AtomicReference<>();
        controller.afterAssign = pid -> {
            runBRootPid.set((long) pid);
            assigned.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService launcher = Executors.newSingleThreadExecutor();
        cleanups.add(() -> {
            release.countDown();
            launcher.shutdownNow();
            try {
                launcher.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Future<?> startB = launcher.submit(() -> {
            try {
                runB.set(controller.start(UUID.randomUUID(), profile));
            } catch (Throwable failure) {
                runBFailure.set(failure);
            }
        });

        assertThat(assigned.await(30, TimeUnit.SECONDS))
                .as("run B must reach the window between its job assignment and its registration")
                .isTrue();
        long runBPid = runBRootPid.get();
        cleanups.add(() -> ProcessHandle.of(runBPid).ifPresent(ProcessHandle::destroyForcibly));
        assertThat(isAlive(runBPid))
                .as("B's root is a live job member while its launch is in flight")
                .isTrue();

        StopProof proofA = controller.stop(runA, Instant.now().plusSeconds(60));

        assertThat(proofA.allWritersStopped())
                .as("B is a live run whose launch is in flight: its root must not be attributed to A as"
                        + " an orphan, so A's proof fails closed")
                .isFalse();
        assertThat(isAlive(runBPid))
                .as("a stop must never kill the root of a launch that is not registered yet")
                .isTrue();

        release.countDown();
        startB.get(30, TimeUnit.SECONDS);
        assertThat(runBFailure.get()).as("run B must complete its start once the window closes").isNull();
        OwnedProcess runBStarted = runB.get();
        assertThat(runBStarted).isNotNull();
        cleanups.add(() -> {
            ProcessHandle.of(runBStarted.rootPid()).ifPresent(ProcessHandle::destroyForcibly);
            controller.release(runBStarted);
        });
        assumeTrue(runBStarted.technique().contains("job-backstop=assign:ok"),
                "run B never became a job member, so the in-flight window has no cross-run claim to"
                        + " exercise: " + runBStarted.technique());
        assertThat(isAlive(runBStarted.rootPid()))
                .as("B's root survived A's stop and is supervised now")
                .isTrue();

        StopProof proofB = controller.stop(runBStarted, Instant.now().plusSeconds(60));

        assertThat(proofB.allWritersStopped())
                .as("once B is registered and supervised, its own stop proof must still account for its"
                        + " writers and be true")
                .isTrue();
        assertThat(isAlive(runBStarted.rootPid())).as("B's stopped root is gone").isFalse();
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

    // ------------------------------------------------------------------ inventory growth helper

    /**
     * F1: the second inventory has to log what appeared after the first one
     * distinctly from what was already there -- a member being killed is still a
     * live process until the OS reports it gone, and the child it spawns inherits
     * the shared job. The comparison is identity-only.
     */
    @Test
    void aMemberThatFirstAppearsInTheFreshInventoryIsIntroduced() {
        assertThat(WindowsProcessController.membersIntroducedSince(
                List.of("35224@134386018254682214", "35928@134386018255000000"),
                List.of("35224@134386018254682214")))
                .containsExactly("35928@134386018255000000");
    }

    @Test
    void aMemberThatDisappearedFromTheFreshInventoryIsNotIntroduced() {
        assertThat(WindowsProcessController.membersIntroducedSince(
                List.of("35224@134386018254682214"),
                List.of("35224@134386018254682214", "35928@134386018255000000")))
                .isEmpty();
    }

    @Test
    void anAbsentPreviousInventoryIntroducesEveryFreshMember() {
        List<String> fresh = List.of("35224@134386018254682214", "35928@134386018255000000");

        assertThat(WindowsProcessController.membersIntroducedSince(fresh, null))
                .as("no previous inventory cannot account for any fresh member")
                .containsExactlyElementsOf(fresh);
        assertThat(WindowsProcessController.membersIntroducedSince(fresh, List.of()))
                .containsExactlyElementsOf(fresh);
        assertThat(WindowsProcessController.membersIntroducedSince(List.<String>of(), fresh))
                .as("an empty fresh inventory introduces nothing")
                .isEmpty();
        assertThat(WindowsProcessController.membersIntroducedSince(null, fresh))
                .as("a null fresh inventory introduces nothing")
                .isEmpty();
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
