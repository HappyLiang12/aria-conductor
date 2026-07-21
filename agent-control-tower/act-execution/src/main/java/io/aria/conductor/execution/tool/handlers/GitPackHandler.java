package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.credential.PackCredentialService;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git pack dispatcher handler — wraps git/gh CLI commands executed in the run workspace.
 * Uses argument arrays (no shell interpretation) to prevent command injection.
 * GITHUB_TOKEN env-injected; bounded concurrency via Semaphore.
 * sandboxMode=NONE (in-process); Docker sandbox hardening deferred to Phase 2.
 */
@Slf4j
@Component("gitPackHandler")
public class GitPackHandler implements ToolHandler {

    private static final int MAX_CONCURRENT_GIT = 4;
    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final int MAX_OUTPUT_BYTES = 1_048_576; // 1MB cap
    private static final String GIT_PACK_ID = "pack-git-0001";

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_GIT);
    private final PackCredentialService credentialService;

    // Whitelist pattern for branch names, paths, remotes
    private static final Pattern SAFE_REF = Pattern.compile("^[A-Za-z0-9._/\\-]+$");
    // URL must start with https:// or git@
    private static final Pattern SAFE_URL = Pattern.compile("^(https://[\\w.@:/\\-]+|git@[\\w.@:/\\-]+)$");

    private static final Set<String> KNOWN_TOOLS = Set.of(
            "git_status", "git_diff", "git_log", "git_add", "git_commit",
            "git_checkout", "git_clone", "git_push", "git_create_pr",
            "git_reset_hard", "git_force_push"
    );

    public GitPackHandler(PackCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        if (!KNOWN_TOOLS.contains(toolName)) {
            return "Error: Unknown git tool: " + toolName;
        }

        String workspaceDir = Objects.toString(arguments.get("_workspaceDir"), null);
        if (workspaceDir == null || workspaceDir.isBlank()) {
            return "Error: git pack requires a run workspace (no _workspaceDir in context)";
        }

        List<String> argv = buildArgv(toolName, arguments);
        if (argv == null) {
            return "Error: Missing or invalid required parameter for " + toolName;
        }

        return executeInWorkspace(argv, workspaceDir, arguments);
    }

    /** Build argument array (no shell interpretation — prevents command injection). */
    private List<String> buildArgv(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "git_status" -> List.of("git", "status", "--short");
            case "git_diff" -> {
                String path = Objects.toString(args.get("path"), "");
                if (path.isEmpty()) yield List.of("git", "diff");
                if (!SAFE_REF.matcher(path).matches()) yield null;
                yield List.of("git", "diff", "--", path);
            }
            case "git_log" -> List.of("git", "log", "--oneline", "-20");
            case "git_add" -> {
                String path = Objects.toString(args.get("path"), ".");
                if (!SAFE_REF.matcher(path).matches()) yield null;
                yield List.of("git", "add", "--", path);
            }
            case "git_commit" -> {
                String message = Objects.toString(args.get("message"), "");
                if (message.isEmpty()) yield null;
                yield List.of("git", "commit", "-m", message);
            }
            case "git_checkout" -> {
                String branch = Objects.toString(args.get("branch"), "");
                boolean create = Boolean.parseBoolean(Objects.toString(args.get("create"), "false"));
                if (branch.isEmpty() || !SAFE_REF.matcher(branch).matches()) yield null;
                yield create ? List.of("git", "checkout", "-b", branch) : List.of("git", "checkout", branch);
            }
            case "git_clone" -> {
                String url = Objects.toString(args.get("url"), "");
                if (url.isEmpty() || !SAFE_URL.matcher(url).matches()) yield null;
                yield List.of("git", "clone", "--", url, ".");
            }
            case "git_push" -> {
                String remote = Objects.toString(args.get("remote"), "origin");
                String branch = Objects.toString(args.get("branch"), "");
                if (!SAFE_REF.matcher(remote).matches()) yield null;
                if (branch.isEmpty()) yield List.of("git", "push", "--", remote);
                if (!SAFE_REF.matcher(branch).matches()) yield null;
                yield List.of("git", "push", "--", remote, branch);
            }
            case "git_create_pr" -> {
                String title = Objects.toString(args.get("title"), "");
                String body = Objects.toString(args.get("body"), "");
                if (title.isEmpty()) yield null;
                List<String> cmd = new ArrayList<>(List.of("gh", "pr", "create", "--title", title, "--fill"));
                if (!body.isEmpty()) { cmd.add("--body"); cmd.add(body); }
                yield cmd;
            }
            case "git_reset_hard" -> List.of("git", "reset", "--hard");
            case "git_force_push" -> {
                String remote = Objects.toString(args.get("remote"), "origin");
                String branch = Objects.toString(args.get("branch"), "");
                if (!SAFE_REF.matcher(remote).matches()) yield null;
                if (branch.isEmpty()) yield List.of("git", "push", "--force", "--", remote);
                if (!SAFE_REF.matcher(branch).matches()) yield null;
                yield List.of("git", "push", "--force", "--", remote, branch);
            }
            default -> null;
        };
    }

    private String executeInWorkspace(List<String> argv, String workspaceDir, Map<String, Object> args) {
        try {
            if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                return "Error: Git concurrency limit reached — try again shortly";
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(argv);
                pb.directory(new File(workspaceDir));
                pb.redirectErrorStream(true);

                // Inject GITHUB_TOKEN from credential store (never logged)
                String token = credentialService.resolve(GIT_PACK_ID, null, "GITHUB_TOKEN");
                if (token != null) {
                    pb.environment().put("GITHUB_TOKEN", token);
                    pb.environment().put("GH_TOKEN", token);
                }

                Process p = pb.start();
                // Wait first, then read (prevents infinite block on hung process)
                boolean finished = p.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                    return "Error: Git command timed out after " + DEFAULT_TIMEOUT_MS + "ms";
                }
                byte[] rawOutput = p.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
                String output = new String(rawOutput, StandardCharsets.UTF_8);
                int exitCode = p.exitValue();
                if (exitCode != 0) {
                    return "Exit code: " + exitCode + "\n" + output.trim();
                }
                return output.trim().isEmpty() ? "(no output)" : output.trim();
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Git execution interrupted";
        } catch (Exception e) {
            log.error("Git pack execution failed", e);
            return "Error: " + e.getMessage();
        }
    }
}
