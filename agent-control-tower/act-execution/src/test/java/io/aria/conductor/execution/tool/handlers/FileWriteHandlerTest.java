package io.aria.conductor.execution.tool.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour + security tests for {@link FileWriteHandler}.
 *
 * <p>The handler jails all writes inside a workspace directory. Absolute paths and
 * {@code ../} traversal that escapes the jail must be rejected before any byte is written.
 * The {@code _workspaceDir} run argument overrides the configured default workspace.
 */
class FileWriteHandlerTest {

    @TempDir
    Path workspace;

    private FileWriteHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FileWriteHandler();
        ReflectionTestUtils.setField(handler, "workspaceDir", workspace.toString());
    }

    private String write(String path, String content) {
        Map<String, Object> args = new HashMap<>();
        if (path != null) args.put("path", path);
        if (content != null) args.put("content", content);
        return handler.execute(args);
    }

    @Test
    void execute_rejectsMissingPathAndContent() {
        assertThat(write(null, "x")).isEqualTo("Error: Missing required parameter: path");
        assertThat(write("a.txt", null)).isEqualTo("Error: Missing required parameter: content");
        assertThat(write("a.txt", "")).isEqualTo("Error: Missing required parameter: content");
    }

    @ParameterizedTest(name = "absolute path rejected: [{0}]")
    @ValueSource(strings = {"/etc/passwd", "/tmp/evil.txt", "C:\\Windows\\System32\\x.txt"})
    void execute_rejectsAbsolutePaths(String absolute) {
        // Only run OS-relevant absolute forms: Path.of("/...").isAbsolute() is true on POSIX;
        // "C:\..." is absolute on Windows. Either way the message is identical.
        if (!Path.of(absolute).isAbsolute()) {
            return; // not absolute on this OS — skip (the other cases cover the branch)
        }
        assertThat(write(absolute, "data"))
                .isEqualTo("Error: Absolute paths are not allowed. Use a relative path within the workspace.");
    }

    @Test
    void execute_rejectsTraversalEscapingWorkspace() {
        assertThat(write("../escape.txt", "data"))
                .isEqualTo("Error: Path traversal denied: ../escape.txt");
        // The file must NOT have been created outside the jail.
        assertThat(Files.exists(workspace.getParent().resolve("escape.txt"))).isFalse();
    }

    @Test
    void execute_writesFileWithinWorkspace_andReportsCharCount() {
        String result = write("notes/hello.txt", "hello world");

        assertThat(result).isEqualTo("Written: notes/hello.txt (11 chars)");
        Path written = workspace.resolve("notes/hello.txt");
        assertThat(Files.exists(written)).isTrue();
        assertThat(written).hasContent("hello world");
    }

    @Test
    void execute_honoursRunWorkspaceOverride(@TempDir Path runWorkspace) {
        Map<String, Object> args = new HashMap<>();
        args.put("path", "out.txt");
        args.put("content", "abc");
        args.put("_workspaceDir", runWorkspace.toString());

        String result = handler.execute(args);

        assertThat(result).isEqualTo("Written: out.txt (3 chars)");
        // Written into the run workspace, not the configured default.
        assertThat(Files.exists(runWorkspace.resolve("out.txt"))).isTrue();
        assertThat(Files.exists(workspace.resolve("out.txt"))).isFalse();
    }
}
