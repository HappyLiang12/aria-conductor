package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.tool.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Read-only workspace diff endpoint powering the dashboard's code-diff preview at push/PR
 * approval gates. Runs {@code git diff} in the run's isolated workspace (if present) and returns
 * the unified diff text plus a porcelain status summary, bounded to avoid oversized payloads.
 *
 * <p>Safe by construction: git is invoked in argv form (no shell), read-only, jailed to the run's
 * workspace, time-boxed, and any failure (e.g. the workspace is not a git repo) yields an empty
 * diff rather than an error.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/runs")
public class WorkspaceDiffController {

    private static final int MAX_DIFF_CHARS = 100_000;
    private static final long GIT_TIMEOUT_SECONDS = 20;

    private final WorkspaceManager workspaceManager;

    public WorkspaceDiffController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @GetMapping("/{runId}/workspace-diff")
    public ResponseEntity<Map<String, Object>> workspaceDiff(@PathVariable UUID runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId.toString());

        String workspaceDir = workspaceManager.getIfExists(runId);
        if (workspaceDir == null) {
            body.put("hasWorkspace", false);
            body.put("summary", "");
            body.put("diff", "");
            body.put("truncated", false);
            return ResponseEntity.ok(body);
        }
        body.put("hasWorkspace", true);

        String summary = runGit(workspaceDir, "status", "--porcelain");
        String diff = runGit(workspaceDir, "diff");
        if (diff.isBlank()) {
            String staged = runGit(workspaceDir, "diff", "--cached");
            if (!staged.isBlank()) diff = staged;
        }
        boolean truncated = diff.length() > MAX_DIFF_CHARS;
        if (truncated) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n… [diff truncated]";
        }
        body.put("summary", summary);
        body.put("diff", diff);
        body.put("truncated", truncated);
        return ResponseEntity.ok(body);
    }

    /** Run a git subcommand (argv form, no shell) in the workspace; returns "" on any error. */
    private String runGit(String workspaceDir, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workspaceDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "";
            }
            // Non-zero exit (e.g. not a git repo) → treat as empty, not an error.
            return p.exitValue() == 0 ? output : "";
        } catch (Exception e) {
            log.debug("git {} failed in {}: {}", String.join(" ", args), workspaceDir, e.getMessage());
            return "";
        }
    }
}
