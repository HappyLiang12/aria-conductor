package io.aria.conductor.execution.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

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
}
