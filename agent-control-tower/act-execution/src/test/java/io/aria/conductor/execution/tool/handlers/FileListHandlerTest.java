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
 * Behaviour + security tests for {@link FileListHandler}.
 *
 * <p>Directory listings are jailed to the per-run {@code _workspaceDir} (when supplied) or the
 * project root. Traversal escaping the jail is denied, non-existent and non-directory targets
 * are reported distinctly, and entries are rendered sorted with a trailing slash for directories.
 */
class FileListHandlerTest {

    @TempDir
    Path root;

    private FileListHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FileListHandler();
        ReflectionTestUtils.setField(handler, "projectRoot", root.toString());
    }

    private String list(String path, String workspaceDir) {
        Map<String, Object> args = new HashMap<>();
        if (path != null) args.put("path", path);
        if (workspaceDir != null) args.put("_workspaceDir", workspaceDir);
        return handler.execute(args);
    }

    @Test
    void execute_listsEntriesSorted_withDirectoryTrailingSlash() throws IOException {
        Files.writeString(root.resolve("beta.txt"), "b");
        Files.writeString(root.resolve("alpha.txt"), "a");
        Files.createDirectory(root.resolve("sub"));

        String result = list(".", null);

        assertThat(result)
                .startsWith("Files in '.':")
                .contains("alpha.txt")
                .contains("beta.txt")
                .contains("sub/");
        // Sorted: alpha before beta before sub (directory slash sorts after by name).
        assertThat(result.indexOf("alpha.txt")).isLessThan(result.indexOf("beta.txt"));
    }

    @Test
    void execute_reportsEmptyDirectory() {
        assertThat(list(".", null)).isEqualTo("Directory '.' is empty.");
    }

    @Test
    void execute_deniesTraversal() {
        assertThat(list("../", null)).isEqualTo("Error: Path traversal denied: ../");
    }

    @Test
    void execute_reportsDirectoryNotFound() {
        assertThat(list("nope", null)).isEqualTo("Error: Directory not found: nope");
    }

    @Test
    void execute_reportsNotADirectory() throws IOException {
        Files.writeString(root.resolve("file.txt"), "x");
        assertThat(list("file.txt", null)).isEqualTo("Error: Not a directory: file.txt");
    }

    @Test
    void execute_listsWithinRunWorkspace_whenWorkspaceDirGiven(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("only.txt"), "1");

        assertThat(list(".", workspace.toString()))
                .startsWith("Files in '.':")
                .contains("only.txt");
    }
}
