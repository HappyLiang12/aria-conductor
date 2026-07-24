package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.credential.PackCredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Behaviour + security tests for {@link GitPackHandler}, focused on the validation and
 * allowlist gates that run BEFORE any git process is spawned (kept hermetic — no real git
 * invocation). Each rejection path is asserted to never touch the credential service, which
 * proves the GITHUB_TOKEN is only resolved when a valid argv has been constructed (and hence
 * is never leaked for malformed/injection-shaped input).
 */
@ExtendWith(MockitoExtension.class)
class GitPackHandlerTest {

    @Mock
    private PackCredentialService credentialService;

    private GitPackHandler handler() {
        return new GitPackHandler(credentialService);
    }

    private String exec(String toolName, String workspaceDir, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>(extra);
        args.put("toolName", toolName);
        if (workspaceDir != null) args.put("_workspaceDir", workspaceDir);
        return handler().execute(args);
    }

    @Test
    void execute_rejectsUnknownGitTool() {
        String result = exec("git_frobnicate", "/tmp/ws", Map.of());
        assertThat(result).isEqualTo("Error: Unknown git tool: git_frobnicate");
        verifyNoInteractions(credentialService);
    }

    @Test
    void execute_rejectsMissingWorkspace() {
        Map<String, Object> args = new HashMap<>();
        args.put("toolName", "git_status");
        args.put("_runId", "run-77");
        String result = handler().execute(args);

        assertThat(result)
                .startsWith("Error: git pack requires a run workspace but none was provisioned")
                .contains("run-77");
        verifyNoInteractions(credentialService);
    }

    @Test
    void execute_rejectsCloneIntoNonEmptyWorkspace(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("existing.txt"), "x");

        String result = exec("git_clone", ws.toString(), Map.of("url", "https://github.com/a/b.git"));

        assertThat(result).isEqualTo("Error: Workspace is not empty — git clone requires an empty directory");
        verifyNoInteractions(credentialService);
    }

    @ParameterizedTest(name = "{0} with invalid args -> rejected before spawn")
    @CsvSource({
            "git_commit,",                       // empty message
            "git_checkout,",                     // empty branch
            "git_create_pr,",                    // empty title
    })
    void execute_rejectsMissingRequiredParameter(String toolName, String ignored, @TempDir Path ws) {
        String result = exec(toolName, ws.toString(), Map.of());

        assertThat(result).isEqualTo("Error: Missing or invalid required parameter for " + toolName);
        verifyNoInteractions(credentialService);
    }

    @ParameterizedTest(name = "injection-shaped {0} arg rejected")
    @CsvSource({
            "git_checkout,branch,bad;name",
            "git_add,path,foo;rm -rf /",
            "git_diff,path,foo|cat",
            "git_push,remote,evil remote",
            "git_clone,url,ftp://evil/repo",
    })
    void execute_rejectsUnsafeArgumentsBeforeSpawn(String toolName, String key, String value, @TempDir Path ws) {
        String result = exec(toolName, ws.toString(), Map.of(key, value));

        assertThat(result).isEqualTo("Error: Missing or invalid required parameter for " + toolName);
        // The token is never resolved for rejected argv, so it can never be injected/leaked.
        verifyNoInteractions(credentialService);
    }
}
