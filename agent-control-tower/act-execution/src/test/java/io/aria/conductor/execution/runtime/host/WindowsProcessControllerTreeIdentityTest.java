package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.LaunchProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F33, critical: the descendant walk trusted {@code PROCESSENTRY32W.th32ParentProcessID}
 * without validating the link against creation time. Live evidence (QA stop proof of
 * run {@code be18e519}): the backend JVM (8312, created 15:44:28.7326Z) and its
 * supervisor (29396, 15:44:28.7499Z) were listed as descendants of the QA root
 * (33680, 15:56:46.0377Z) -- 12 minutes <em>older</em> than the root. The backend's
 * dead launcher pid had been recycled by the QA root, the stale parent pid made every
 * older process look like a child, and the stop proof killed the backend and the
 * supervisor. A process can never be a child of a process created after it, so the
 * tree must not follow a link whose child predates its parent, and no kill may ever
 * signal this controller's own JVM or its supervision channel.
 *
 * <p>These tests run on the real OS: the positive tree shape is a real three-level
 * process family, and the self-kill guard is exercised on the real supervision
 * channel. The pid-reuse shape itself cannot be forced deterministically on a live
 * host; its root boundary is unit-tested through the same filter the live read uses,
 * and the per-level rule is reviewed against the walker.
 */
@Timeout(240)
class WindowsProcessControllerTreeIdentityTest {

    private final List<AutoCloseable> cleanups = new ArrayList<>();
    private final List<Path> workingDirectories = new ArrayList<>();

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
     * The supervision channel must never signal itself: it is the only way to stop
     * the run, so a self-kill strands every later control action and the stop proof
     * it was answering. The guard is tested on the real channel, and the channel must
     * still answer after the refusal.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aSupervisorNeverSignalsItself() {
        WindowsProcessController controller = new WindowsProcessController();
        try {
            long supervisorPid = controller.supervisorPid();
            String self = controller.supervisorCommand("self");
            assertThat(self)
                    .as("the channel's self record is its own pid and creation identity")
                    .startsWith(supervisorPid + "@");

            assertThat(controller.supervisorCommand("kill " + self))
                    .as("a kill of the supervision channel's own record must be refused, not applied")
                    .isEqualTo(self + ":refused:self");
            assertThat(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false))
                    .as("the supervision channel must survive its own kill request")
                    .isTrue();
            assertThat(controller.supervisorCommand("self"))
                    .as("the channel must still answer after the refusal")
                    .isEqualTo(self);
        } finally {
            controller.close();
        }
    }

    /**
     * The creation-order rule must never cut a real tree: a root, a child and a
     * grandchild spawned on the OS are all enumerated, and every enumerated record
     * is at least as young as the root. The supervisor is older than the root in
     * this fixture (it is created to launch the run) and must never appear.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aVerifiedTreeKeepsItsRealDescendantsAndNothingOlderThanTheRoot() throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        assertThat(systemRoot).as("a Windows host always exports SystemRoot").isNotBlank();
        Path cmd = Path.of(systemRoot, "System32", "cmd.exe");
        Path ping = Path.of(systemRoot, "System32", "PING.EXE");
        assertThat(cmd).as("the fixture root is the OS cmd.exe").isRegularFile();
        assertThat(ping).as("the fixture descendants are the OS ping.exe").isRegularFile();

        Path working = Files.createTempDirectory("host-tree-identity");
        workingDirectories.add(working);
        // Unique image names are the discriminator: ProcessHandle.info().command() is
        // readable for another process (a command line is not).
        Path child = working.resolve("f33-child.exe");
        Path grandchild = working.resolve("f33-grandchild.exe");
        Files.copy(cmd, child);
        Files.copy(ping, grandchild);
        Files.writeString(working.resolve("root.bat"), String.join("\r\n",
                "@echo off",
                "start /b \"\" \"%~dp0f33-child.exe\" /d /s /c \"%~dp0child.bat\"",
                "\"%~dp0f33-grandchild.exe\" -n 120 127.0.0.1 >nul",
                ""));
        Files.writeString(working.resolve("child.bat"), String.join("\r\n",
                "@echo off",
                "start /b \"\" \"%~dp0f33-grandchild.exe\" -n 120 127.0.0.1",
                "\"%~dp0f33-grandchild.exe\" -n 120 127.0.0.1 >nul",
                ""));

        WindowsProcessController controller = new WindowsProcessController();
        LaunchProfile profile = new LaunchProfile(
                List.of(cmd.toString(), "/d", "/s", "/c", "root.bat"),
                Map.of("SystemRoot", systemRoot, "windir", systemRoot), working.toString());
        OwnedProcess owned = controller.start(UUID.randomUUID(), profile);
        Process root = owned.liveProcess();
        cleanups.add(() -> {
            ProcessHandle.of(owned.rootPid()).ifPresent(ProcessHandle::destroyForcibly);
            root.destroyForcibly();
            root.waitFor(10, TimeUnit.SECONDS);
            controller.release(owned);
            controller.close();
        });

        long childPid = awaitDescendant(root.pid(), "f33-child.exe", 20_000);
        long grandchildPid = awaitDescendant(childPid, "f33-grandchild.exe", 20_000);
        cleanups.add(() -> ProcessHandle.of(grandchildPid).ifPresent(ProcessHandle::destroyForcibly));
        cleanups.add(() -> ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly));

        long rootTicks = Long.parseLong(owned.rootCreationIdentity());
        List<String[]> entries = treeEntries(controller, owned.rootPid());
        List<String> pids = entries.stream().map(entry -> entry[0]).toList();

        assertThat(pids).as("the verified root is the first level of its own tree")
                .contains(Long.toString(owned.rootPid()));
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry[0]).isEqualTo(Long.toString(owned.rootPid()));
            assertThat(entry[2]).as("the root is at depth 0").isEqualTo("0");
        });
        assertThat(pids).as("a real child is enumerated").contains(Long.toString(childPid));
        assertThat(pids).as("a real grandchild is enumerated").contains(Long.toString(grandchildPid));
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry[0]).isEqualTo(Long.toString(grandchildPid));
            assertThat(Integer.parseInt(entry[2]))
                    .as("the grandchild is enumerated below its parent, never as a direct child")
                    .isGreaterThanOrEqualTo(2);
        });
        for (String[] entry : entries) {
            assertThat(Long.parseLong(entry[1]))
                    .as("a process cannot be a child of a process created after it: %s",
                            String.join("@", entry))
                    .isGreaterThanOrEqualTo(rootTicks - 1_000_000L);
        }
        long supervisorPid = controller.supervisorPid();
        assertThat(pids)
                .as("the supervision channel predates the run and is never one of its descendants")
                .doesNotContain(Long.toString(supervisorPid));
    }

    /**
     * The root boundary of a tree read: a record older than the root cannot be one
     * of its writers, including the live shape where an older stranger is linked to
     * the run through a recycled parent pid. The construction tolerance is kept on
     * both sides of the boundary and a malformed record fails the read closed.
     */
    @Test
    void aTreeReadKeepsOnlyRecordsCreatedAtOrAfterTheRoot() {
        long root = 134_386_000_000_000_000L;
        long tolerance = WindowsProcessController.CREATION_ORDER_TOLERANCE_TICKS;
        List<String> kept = WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100@" + (root - tolerance - 1),
                        "101@" + (root - tolerance),
                        "102@" + root,
                        "103@" + (root + 1)),
                102L, Long.toString(root));

        assertThat(kept)
                .as("only the record older than the root by more than the tolerance is dropped")
                .containsExactly("101@" + (root - tolerance), "102@" + root, "103@" + (root + 1));
    }

    /**
     * F33 review P2: the read must be anchored on the identity the controller verified.
     * A root pid reused between the ownership verdict and the enumeration carries a
     * different creation identity; walking that tree would enumerate a stranger's
     * descendants and signal them. A non-empty tree without the root record is not this
     * run's tree at all. An empty tree is the dead-root read and stays legal.
     */
    @Test
    void aTreeNotAnchoredOnTheRecordedRootIdentityFailsTheReadClosed() {
        long root = 5_000_000L;
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("7005@5000001", "9001@6000000"), 9001L, Long.toString(root)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different creation identity");
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("7005@5000001"), 9001L, Long.toString(root)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain the run root");
        assertThat(WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of(), 9001L, Long.toString(root))).isEmpty();
    }

    /**
     * Both wire shapes of a gap parse to the same marker: the command handler's
     * "unverifiable:pid@depth" and the raw walker's "unverifiable:pid@parentTicks@depth".
     */
    @Test
    void bothGapShapesParseToTheSameMarker() {
        assertThat(WindowsProcessController.recordsFrom(
                "unverifiable:7004@1;7005@9002@1;9001@9001@0"))
                .containsExactly("unverifiable:7004", "7005@9002", "9001@9001");
        assertThat(WindowsProcessController.recordsFrom(
                "unverifiable:7004@5000000@1;9001@5000000@0"))
                .containsExactly("unverifiable:7004", "9001@5000000");
    }

    /**
     * A subtree the OS could not identify is carried as a gap, never as a killable
     * identity; the proof meets it and fails closed.
     */
    @Test
    void anUnidentifiableSubtreeIsCarriedAsAGap() {
        List<String> kept = WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("unverifiable:7004", "7005@5000001", "9001@5000000"),
                9001L, "5000000");

        assertThat(kept).containsExactly("unverifiable:7004", "7005@5000001", "9001@5000000");
        assertThat(WindowsProcessController.isTreeGap("unverifiable:7004")).isTrue();
        assertThat(WindowsProcessController.isTreeGap("7005@5000001")).isFalse();
    }

    @Test
    void aMalformedTreeRecordFailsTheReadClosed() {
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100@not-ticks"), 1L, "1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100"), 1L, "1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100@"), 1L, "1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                java.util.Collections.singletonList(null), 1L, "1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnreadableRootIdentityFailsTheReadClosed() {
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100@200"), 1L, "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100@200"), 1L, "-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsProcessController.descendantsCreatedAtOrAfterRoot(
                List.of("100@200"), 1L, "not-ticks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theGuardNamesThisControllerItsChannelAndARecordThatPredatesTheRun() {
        long root = 134_386_000_000_000_000L;
        long tolerance = WindowsProcessController.CREATION_ORDER_TOLERANCE_TICKS;

        assertThat(WindowsProcessController.refusalOf("100@" + root, 100L, 200L, root))
                .as("the backend JVM is never signalled, not even by a record it once matched")
                .isEqualTo("refused:controller");
        assertThat(WindowsProcessController.refusalOf("200@" + root, 100L, 200L, root))
                .isEqualTo("refused:supervisor");
        assertThat(WindowsProcessController.refusalOf("100@" + (root + 1), 100L, 200L, root))
                .as("the controller pid wins over every other classification")
                .isEqualTo("refused:controller");
        assertThat(WindowsProcessController.refusalOf("100@junk", 100L, 200L, root))
                .as("the controller pid is refused even when its ticks are malformed")
                .isEqualTo("refused:controller");
        assertThat(WindowsProcessController.refusalOf("300@" + (root - tolerance - 1), 100L, 200L, root))
                .isEqualTo("refused:predates-run");
        assertThat(WindowsProcessController.refusalOf("300@" + (root - tolerance), 100L, 200L, root))
                .as("a record at the boundary is not refused")
                .isNull();
        assertThat(WindowsProcessController.refusalOf("300@" + (root + 1), 100L, 200L, root)).isNull();
        assertThat(WindowsProcessController.refusalOf("300@junk", 100L, 200L, root))
                .as("a record without creation ticks names no identity and is never signalled")
                .isEqualTo("refused:malformed");
        assertThat(WindowsProcessController.refusalOf("300", 100L, 200L, root))
                .as("a bare pid would match in Test-Record; the guard refuses it first")
                .isEqualTo("refused:malformed");
        assertThat(WindowsProcessController.refusalOf("unverifiable:7004", 100L, 200L, root))
                .isEqualTo("refused:malformed");
        assertThat(WindowsProcessController.refusalOf(null, 100L, 200L, root))
                .isEqualTo("refused:malformed");
    }

    /**
     * The walker's per-link boundary, exercised through the supervisor's own
     * classifier: the C# tolerance must be the Java one (the test computes the
     * boundaries from {@link WindowsProcessController#CREATION_ORDER_TOLERANCE_TICKS}),
     * so the two cannot drift apart.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theSupervisorAppliesTheSameCreationOrderBoundaryAsTheJavaFilter() {
        WindowsProcessController controller = new WindowsProcessController();
        try {
            long tolerance = WindowsProcessController.CREATION_ORDER_TOLERANCE_TICKS;
            long parent = 134_386_000_000_000_000L;
            assertThat(controller.supervisorCommand("link " + (parent - tolerance) + " " + parent))
                    .as("a child exactly at the tolerance is still a descendant")
                    .isEqualTo("descendant");
            assertThat(controller.supervisorCommand("link " + (parent - tolerance - 1) + " " + parent))
                    .as("one tick beyond the tolerance is a stale parent link")
                    .isEqualTo("stale");
            assertThat(controller.supervisorCommand("link " + (parent + 1) + " " + parent))
                    .isEqualTo("descendant");
        } finally {
            controller.close();
        }
    }

    /**
     * The guarded kill path on the real OS: this controller's JVM, its supervision
     * channel and a real process older than the run are refused and stay alive, while
     * the run's own root is still killed by the same batch path (positive control).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theKillPathNeverSignalsTheControllerItsChannelOrAProcessThatPredatesTheRun() throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        assertThat(systemRoot).as("a Windows host always exports SystemRoot").isNotBlank();
        Path ping = Path.of(systemRoot, "System32", "PING.EXE");
        assertThat(ping).as("the fixture is the OS ping.exe").isRegularFile();
        Path working = Files.createTempDirectory("host-kill-guard");
        workingDirectories.add(working);

        Process older = new ProcessBuilder(ping.toString(), "-n", "120", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        cleanups.add(() -> {
            older.destroyForcibly();
            older.waitFor(10, TimeUnit.SECONDS);
        });
        // The guard's boundary is the class tolerance; the fixture must predate the run
        // root by more than it, or the batch would be sent and the fixture killed.
        Thread.sleep(150);

        WindowsProcessController controller = new WindowsProcessController();
        LaunchProfile profile = new LaunchProfile(
                List.of(ping.toString(), "-n", "120", "127.0.0.1"),
                Map.of("SystemRoot", systemRoot, "windir", systemRoot), working.toString());
        OwnedProcess owned = controller.start(UUID.randomUUID(), profile);
        Process root = owned.liveProcess();
        cleanups.add(() -> {
            root.destroyForcibly();
            root.waitFor(10, TimeUnit.SECONDS);
            controller.release(owned);
            controller.close();
        });

        String olderRecord = controller.supervisorCommand("identity " + older.pid());
        assertThat(olderRecord).as("the pre-run process is identity-readable").startsWith(older.pid() + "@");
        long controllerPid = ProcessHandle.current().pid();
        long supervisorPid = controller.supervisorPid();
        String controllerRecord = controller.supervisorCommand("identity " + controllerPid);
        String supervisorRecord = controller.supervisorCommand("identity " + supervisorPid);
        assertThat(controllerRecord).startsWith(controllerPid + "@");
        assertThat(supervisorRecord).startsWith(supervisorPid + "@");
        long olderTicks = Long.parseLong(olderRecord.substring(olderRecord.indexOf('@') + 1));
        long rootTicks = Long.parseLong(owned.rootCreationIdentity());
        assertThat(rootTicks - olderTicks)
                .as("the pre-run process really predates the run root beyond the guard's tolerance")
                .isGreaterThan(WindowsProcessController.CREATION_ORDER_TOLERANCE_TICKS);

        Map<String, String> refused = controller.killRecords(owned,
                List.of(controllerRecord, supervisorRecord, olderRecord,
                        Long.toString(older.pid()), "not-a-record", "unverifiable:7004"));
        assertThat(refused.get(controllerRecord)).isEqualTo("refused:controller");
        assertThat(refused.get(supervisorRecord)).isEqualTo("refused:supervisor");
        assertThat(refused.get(olderRecord)).isEqualTo("refused:predates-run");
        assertThat(refused.get(Long.toString(older.pid())))
                .as("a bare pid would match in Test-Record and is refused before it is sent")
                .isEqualTo("refused:malformed");
        assertThat(refused.get("not-a-record")).isEqualTo("refused:malformed");
        assertThat(refused.get("unverifiable:7004"))
                .as("a gap names no identity and is never sent to the kill path")
                .isEqualTo("refused:malformed");
        assertThat(ProcessHandle.of(controllerPid).map(ProcessHandle::isAlive).orElse(false))
                .as("the backend JVM is still running its own test").isTrue();
        assertThat(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false))
                .as("the supervision channel survived the refused batch").isTrue();
        assertThat(older.isAlive()).as("a process that predates the run was not signalled").isTrue();
        assertThat(controller.supervisorCommand("self")).startsWith(supervisorPid + "@");

        Map<String, String> applied = controller.killRecords(owned,
                List.of(owned.rootPid() + "@" + owned.rootCreationIdentity()));
        assertThat(applied.get(owned.rootPid() + "@" + owned.rootCreationIdentity()))
                .as("the positive control: the run's own root is still killed by the guarded path")
                .isEqualTo("ok");
        assertThat(root.waitFor(30, TimeUnit.SECONDS)).as("the run root really died").isTrue();
    }

    private static List<String[]> treeEntries(WindowsProcessController controller, long rootPid) {
        String payload = controller.supervisorCommand("tree " + rootPid);
        List<String[]> entries = new ArrayList<>();
        for (String entry : payload.split(";", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split("@", -1);
            assertThat(parts).as("tree entry %s is pid@created@depth", entry).hasSize(3);
            entries.add(parts);
        }
        return entries;
    }

    private static long awaitDescendant(long ancestorPid, String imageName, long timeoutMillis)
            throws InterruptedException {
        long limit = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < limit) {
            ProcessHandle ancestor = ProcessHandle.of(ancestorPid).orElse(null);
            if (ancestor != null) {
                List<ProcessHandle> descendants = ancestor.descendants().toList();
                for (ProcessHandle descendant : descendants) {
                    String command = descendant.info().command().orElse("");
                    if (command.toLowerCase().endsWith(imageName.toLowerCase())) {
                        return descendant.pid();
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no descendant of " + ancestorPid + " runs " + imageName);
    }

    private static void deleteTreeBestEffort(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }
}
