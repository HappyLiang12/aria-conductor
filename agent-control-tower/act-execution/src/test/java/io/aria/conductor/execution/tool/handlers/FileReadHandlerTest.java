package io.aria.conductor.execution.tool.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour + security tests for {@link FileReadHandler}.
 *
 * <p>Reads are jailed either to the per-run {@code _workspaceDir} (when supplied) or to the
 * configured project root. {@code ../} traversal that escapes the jail is denied, missing
 * files are reported clearly, and oversized files are truncated at 512KB.
 */
class FileReadHandlerTest {

    @TempDir
    Path root;

    private FileReadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FileReadHandler();
        ReflectionTestUtils.setField(handler, "projectRoot", root.toString());
    }

    private String read(String path, String workspaceDir) {
        Map<String, Object> args = new HashMap<>();
        if (path != null) args.put("path", path);
        if (workspaceDir != null) args.put("_workspaceDir", workspaceDir);
        return handler.execute(args);
    }

    @Test
    void execute_rejectsMissingPath() {
        assertThat(read(null, null)).isEqualTo("Error: Missing required parameter: path");
        assertThat(read("", null)).isEqualTo("Error: Missing required parameter: path");
    }

    @Test
    void execute_readsFileFromProjectRoot() throws IOException {
        Files.writeString(root.resolve("data.txt"), "payload-42");

        assertThat(read("data.txt", null)).isEqualTo("payload-42");
    }

    @Test
    void execute_readsFileFromRunWorkspace_whenWorkspaceDirGiven(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("in.txt"), "from-workspace");

        assertThat(read("in.txt", workspace.toString())).isEqualTo("from-workspace");
    }

    @Test
    void execute_deniesTraversalUnderProjectRoot() {
        assertThat(read("../secret.txt", null)).isEqualTo("Error: Path traversal denied: ../secret.txt");
    }

    @Test
    void execute_deniesTraversalUnderRunWorkspace(@TempDir Path workspace) {
        assertThat(read("../../etc/passwd", workspace.toString()))
                .isEqualTo("Error: Path traversal denied: ../../etc/passwd");
    }

    @Test
    void execute_reportsFileNotFound() {
        assertThat(read("missing.txt", null)).isEqualTo("Error: File not found: missing.txt");
    }

    @Test
    void execute_truncatesFilesLargerThan512KB() throws IOException {
        String big = "y".repeat(512_000 + 100);
        Files.writeString(root.resolve("big.txt"), big);

        String result = read("big.txt", null);

        assertThat(result).endsWith("\n... [truncated at 512KB]");
        // 512000 kept + the truncation marker; the trailing 100 chars must be gone.
        assertThat(result).hasSize(512_000 + "\n... [truncated at 512KB]".length());
    }
}
