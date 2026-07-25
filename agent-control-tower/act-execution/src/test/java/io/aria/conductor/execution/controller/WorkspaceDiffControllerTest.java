package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.tool.WorkspaceManager;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceDiffControllerTest extends WebMvcTestBase {

    private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
    private final MockMvc mvc = mockMvcFor(new WorkspaceDiffController(workspaceManager));

    @TempDir
    Path workspace;

    @Test
    void workspaceDiff_noWorkspaceForRun_returnsEmptyDiffMarker() throws Exception {
        UUID runId = UUID.randomUUID();
        when(workspaceManager.getIfExists(runId)).thenReturn(null);

        mvc.perform(get("/api/v1/runs/" + runId + "/workspace-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.hasWorkspace").value(false))
                .andExpect(jsonPath("$.summary").value(""))
                .andExpect(jsonPath("$.diff").value(""))
                .andExpect(jsonPath("$.truncated").value(false));
        verify(workspaceManager).getIfExists(runId);
    }

    @Test
    void workspaceDiff_workspaceIsNotGitRepo_returnsEmptyDiffNotError() throws Exception {
        UUID runId = UUID.randomUUID();
        when(workspaceManager.getIfExists(runId)).thenReturn(workspace.toString());

        // Non-repo directory: git exits non-zero → controller must degrade to empty output.
        mvc.perform(get("/api/v1/runs/" + runId + "/workspace-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasWorkspace").value(true))
                .andExpect(jsonPath("$.summary").value(""))
                .andExpect(jsonPath("$.diff").value(""))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void workspaceDiff_dirtyGitWorkspace_returnsUnifiedDiffAndSummary() throws Exception {
        assumeTrue(gitAvailable(), "git binary not available on PATH");
        UUID runId = UUID.randomUUID();
        initRepoWithCommit(workspace);
        Files.writeString(workspace.resolve("app.txt"), "original line\nmodified line\n");
        when(workspaceManager.getIfExists(runId)).thenReturn(workspace.toString());

        mvc.perform(get("/api/v1/runs/" + runId + "/workspace-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasWorkspace").value(true))
                .andExpect(jsonPath("$.summary").value(containsString("app.txt")))
                .andExpect(jsonPath("$.diff").value(containsString("+modified line")))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void workspaceDiff_stagedOnlyChanges_fallsBackToCachedDiff() throws Exception {
        assumeTrue(gitAvailable(), "git binary not available on PATH");
        UUID runId = UUID.randomUUID();
        initRepoWithCommit(workspace);
        Files.writeString(workspace.resolve("app.txt"), "original line\nstaged line\n");
        assumeTrue(runGit(workspace, "add", "app.txt"), "git add failed");
        when(workspaceManager.getIfExists(runId)).thenReturn(workspace.toString());

        // Unstaged diff is empty; the endpoint must surface `git diff --cached` instead.
        mvc.perform(get("/api/v1/runs/" + runId + "/workspace-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasWorkspace").value(true))
                .andExpect(jsonPath("$.diff").value(containsString("+staged line")));
    }

    // ── git fixture helpers (argv form, no shell) ───────────────────────────

    private static void initRepoWithCommit(Path dir) throws Exception {
        assumeTrue(runGit(dir, "init"), "git init failed");
        Files.writeString(dir.resolve("app.txt"), "original line\n");
        assumeTrue(runGit(dir, "add", "app.txt"), "git add failed");
        assumeTrue(runGit(dir,
                        "-c", "user.email=test@aria.io", "-c", "user.name=aria-test",
                        "-c", "commit.gpgsign=false", "commit", "-m", "init"),
                "git commit failed");
    }

    private static boolean gitAvailable() {
        return runGit(Path.of("."), "--version");
    }

    private static boolean runGit(Path dir, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
