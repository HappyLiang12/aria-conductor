package io.aria.conductor.execution.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    /** Outside the platform-owned workspace root: must never be reachable by a deletion walk. */
    @TempDir
    Path external;

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkspaceManager(tempDir.toString());
    }

    @Test
    void provisionCreatesDirectory() {
        UUID runId = UUID.randomUUID();
        String path = manager.provision(runId);
        assertNotNull(path);
        assertTrue(Files.isDirectory(Path.of(path)));
        assertTrue(path.contains(runId.toString()));
    }

    @Test
    void provisionIsIdempotent() {
        UUID runId = UUID.randomUUID();
        String first = manager.provision(runId);
        String second = manager.provision(runId);
        assertEquals(first, second);
    }

    @Test
    void getOrProvisionIsIdempotentAndMatchesProvision() {
        UUID runId = UUID.randomUUID();
        String first = manager.getOrProvision(runId);
        String second = manager.getOrProvision(runId);
        assertEquals(first, second);
        assertTrue(Files.isDirectory(Path.of(first)));
        // Same contract entry point as provision()
        assertEquals(first, manager.provision(runId));
    }

    @Test
    void resolveRejectsTraversal() {
        UUID runId = UUID.randomUUID();
        manager.provision(runId);
        assertThrows(SecurityException.class, () -> manager.resolve(runId, "../../etc/passwd"));
    }

    @Test
    void resolveRejectsAbsoluteEscape() {
        UUID runId = UUID.randomUUID();
        manager.provision(runId);
        // On Unix, an absolute path like /etc/passwd should be rejected
        // because it doesn't start with the workspace
        assertThrows(SecurityException.class, () -> manager.resolve(runId, "/etc/passwd"));
    }

    @Test
    void resolveAllowsRelativePath() {
        UUID runId = UUID.randomUUID();
        manager.provision(runId);
        Path resolved = manager.resolve(runId, "src/main/App.java");
        assertTrue(resolved.toString().contains(runId.toString()));
        assertTrue(resolved.toString().endsWith("src/main/App.java".replace("/", java.io.File.separator)));
    }

    @Test
    void resolveRefusesALinkInsideTheRunWorkspaceThatEscapesIt() throws Exception {
        UUID runId = UUID.randomUUID();
        Path workspace = Path.of(manager.provision(runId));

        // The agent's own shell tool can plant this: a junction inside the run workspace
        // pointed at a directory the platform never owned. The jail is lexical today, so
        // resolve() hands a file tool a path under the link that really lands outside.
        Path outside = Files.createDirectories(external.resolve("resolve-escape-target"));
        Files.writeString(outside.resolve("planted.txt"), "outside\n");
        Path link = workspace.resolve("escape");
        createDirectoryAlias(link, outside);
        try {
            SecurityException existing = assertThrows(SecurityException.class,
                    () -> manager.resolve(runId, "escape/planted.txt"));
            assertEquals("Path escapes workspace through a link at " + link + ": escape/planted.txt",
                    existing.getMessage());
            SecurityException notYetCreated = assertThrows(SecurityException.class,
                    () -> manager.resolve(runId, "escape/planted-new.txt"));
            assertEquals("Path escapes workspace through a link at " + link + ": escape/planted-new.txt",
                    notYetCreated.getMessage());
            SecurityException onTheLinkItself = assertThrows(SecurityException.class,
                    () -> manager.resolve(runId, "escape"));
            assertEquals("Path escapes workspace through a link at " + link + ": escape",
                    onTheLinkItself.getMessage());

            // Nothing was created at the link target and the target keeps its bytes.
            assertFalse(Files.exists(outside.resolve("planted-new.txt")));
            assertEquals("outside\n", Files.readString(outside.resolve("planted.txt")));
        } finally {
            Files.deleteIfExists(link);
        }

        // The legacy shape is unchanged: a plain directory tree resolves exactly as before.
        Files.writeString(workspace.resolve("legit.txt"), "legit\n");
        assertEquals(workspace.resolve("legit.txt"), manager.resolve(runId, "legit.txt"));
        assertEquals(workspace.resolve("new").resolve("deep").resolve("file.txt"),
                manager.resolve(runId, "new/deep/file.txt"));
    }

    @Test
    void resolveRefusesAPrePlantedLinkAsTheRunDirectory() throws Exception {
        UUID runId = UUID.randomUUID();
        Path outside = Files.createDirectories(external.resolve("resolve-run-dir-target"));
        Files.writeString(outside.resolve("user.txt"), "user-data\n");

        // Planted before the platform ever provisions the run: the per-run directory
        // itself is a link, so every path under it would resolve outside the workspace.
        Path link = tempDir.resolve(runId.toString());
        createDirectoryAlias(link, outside);
        try {
            Path workspace = Path.of(manager.provision(runId));
            assertEquals(link, workspace);

            SecurityException refusal = assertThrows(SecurityException.class,
                    () -> manager.resolve(runId, "user.txt"));
            assertEquals("Path escapes workspace through a planted link at the run directory "
                    + workspace + ": user.txt", refusal.getMessage());

            // The link target is untouched: nothing was adopted from it.
            assertEquals("user-data\n", Files.readString(outside.resolve("user.txt")));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void cleanupRemovesDirectory() {
        UUID runId = UUID.randomUUID();
        String path = manager.provision(runId);
        assertTrue(Files.isDirectory(Path.of(path)));
        manager.cleanup(runId);
        assertFalse(Files.exists(Path.of(path)));
    }

    @Test
    void getIfExistsReturnsNullWhenMissing() {
        assertNull(manager.getIfExists(UUID.randomUUID()));
    }

    @Test
    void getIfExistsReturnsPathWhenPresent() {
        UUID runId = UUID.randomUUID();
        manager.provision(runId);
        assertNotNull(manager.getIfExists(runId));
    }

    @Test
    void cleanupRemovesTheTreeWithoutFollowingALinkOutOfTheOwnedRoot() throws Exception {
        UUID runId = UUID.randomUUID();
        Path workspace = Path.of(manager.provision(runId));
        Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(workspace.resolve("sub").resolve("f.txt"), "platform-file\n");

        Path outside = Files.createDirectories(external.resolve("retained-result"));
        Files.writeString(outside.resolve("README.md"), "outside-content\n");
        Files.createDirectories(outside.resolve("nested"));
        Files.writeString(outside.resolve("nested").resolve("keep.txt"), "nested-content\n");
        Path link = workspace.resolve("linked");
        createDirectoryAlias(link, outside);

        manager.cleanup(runId);

        // The run tree (and with it the link entry) is removed; the link target is untouched.
        assertFalse(Files.exists(workspace));
        assertFalse(Files.exists(link));
        assertTrue(Files.isDirectory(outside));
        assertEquals("outside-content\n", Files.readString(outside.resolve("README.md")));
        assertTrue(Files.isDirectory(outside.resolve("nested")));
        assertEquals("nested-content\n", Files.readString(outside.resolve("nested").resolve("keep.txt")));
    }

    @Test
    void sweeperRemovesTheTreeWithoutFollowingALinkOutOfTheOwnedRoot() throws Exception {
        UUID runId = UUID.randomUUID();
        Path workspace = Path.of(manager.provision(runId));
        Path outside = Files.createDirectories(external.resolve("swept-target"));
        Files.writeString(outside.resolve("retained.txt"), "keep\n");
        createDirectoryAlias(workspace.resolve("linked"), outside);
        Files.setLastModifiedTime(workspace, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        manager.sweepOrphanWorkspaces();

        assertFalse(Files.exists(workspace));
        assertEquals("keep\n", Files.readString(outside.resolve("retained.txt")));
    }

    @Test
    void cleanupRemovesFileLinksAsEntriesAndNeverTheirTargets() throws Exception {
        UUID runId = UUID.randomUUID();
        Path workspace = Path.of(manager.provision(runId));
        Path outside = Files.createDirectories(external.resolve("linked-files"));
        Files.writeString(outside.resolve("keep.txt"), "outside-content\n");
        createFileLink(workspace.resolve("escape.txt"), outside.resolve("keep.txt"));
        createFileLink(workspace.resolve("broken.txt"), outside.resolve("never-created.txt"));

        manager.cleanup(runId);

        // A file whose real path leaves the owned tree is removed as the entry itself: the
        // link goes with the tree, its target keeps its bytes, and a broken link neither
        // aborts the deletion nor reaches anywhere (realPathStaysWithin fails closed).
        assertFalse(Files.exists(workspace));
        assertEquals("outside-content\n", Files.readString(outside.resolve("keep.txt")));
        assertFalse(Files.exists(outside.resolve("never-created.txt")));
    }

    @Test
    void cleanupAndSweeperRefuseToDeleteWhenTheOwnedRootIsReplacedByAJunction() throws Exception {
        // The platform root is adopted as a real directory at construction...
        Path platformRoot = Files.createDirectories(tempDir.resolve("platform-root"));
        WorkspaceManager junctionedRootManager = new WorkspaceManager(platformRoot.toString());

        // ...and is then replaced by a junction onto a tree the platform never owned.
        UUID runId = UUID.randomUUID();
        Path foreign = Files.createDirectories(external.resolve("foreign-workspace-tree"));
        Path victim = Files.createDirectories(foreign.resolve(runId.toString()));
        Files.writeString(victim.resolve("user-data.txt"), "outside-content\n");
        Files.delete(platformRoot);
        createDirectoryAlias(platformRoot, foreign);
        assertTrue(Files.exists(platformRoot.resolve(runId.toString())), "the junction must expose the foreign tree");

        try {
            junctionedRootManager.cleanup(runId);
            assertTrue(Files.exists(victim), "a junction replacing the owned root must not redirect a cleanup");
            assertEquals("outside-content\n", Files.readString(victim.resolve("user-data.txt")));

            // The age-based sweeper is the other consumer of the same deletion path.
            Files.setLastModifiedTime(victim, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));
            junctionedRootManager.sweepOrphanWorkspaces();
            assertTrue(Files.exists(victim), "a junction replacing the owned root must not redirect a sweep");
            assertEquals("outside-content\n", Files.readString(victim.resolve("user-data.txt")));
        } finally {
            Files.deleteIfExists(platformRoot);
        }
    }

    /** Junction on Windows (`mklink /J`), symbolic link elsewhere — the guard used by the sibling test class. */
    private static void createDirectoryAlias(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "mklink /J timed out");
            assertEquals(0, p.exitValue(), "mklink /J failed: " + out);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /** A file link (`mklink` on Windows, a symbolic link elsewhere); the target may not exist. */
    private static void createFileLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "mklink timed out");
            assertEquals(0, p.exitValue(), "mklink failed: " + out);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
