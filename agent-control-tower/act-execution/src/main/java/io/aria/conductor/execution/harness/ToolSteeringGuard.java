package io.aria.conductor.execution.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Steers a weak model away from running git/gh via the ungoverned {@code shell_exec} tool and
 * toward the governed git pack ({@code git_clone/git_commit/git_push/git_create_pr}). When steering
 * is enabled for the active harness profile and the model calls {@code shell_exec} with a git/gh
 * command, the call is intercepted and a BLOCKED result carrying an actionable nudge is returned;
 * the nudge is surfaced back to the model as the tool result so it retries with the correct tool.
 *
 * <p>The result is intentionally BLOCKED (not FAILED) so a steering redirect does not count toward
 * the loop's consecutive-identical-error early termination.
 */
@Slf4j
@Component
public class ToolSteeringGuard {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Returns a nudge result when the action should be steered; empty to let it proceed normally. */
    public Optional<ActionResult> intercept(Action action, HarnessProfile profile) {
        if (action == null || profile == null || profile.steering() == null
                || !profile.steering().shellExecToGitPack()) {
            return Optional.empty();
        }
        if (!"shell_exec".equalsIgnoreCase(action.name())) {
            return Optional.empty();
        }
        String command = extractCommand(action.arguments());
        if (command == null) return Optional.empty();
        String trimmed = command.stripLeading();
        String lower = trimmed.toLowerCase();
        if (!(lower.startsWith("git ") || lower.equals("git")
                || lower.startsWith("gh ") || lower.equals("gh"))) {
            return Optional.empty();
        }
        String nudge = buildNudge(lower);
        log.info("Steering shell_exec '{}' to the governed git pack", truncate(trimmed, 80));
        return Optional.of(ActionResult.blocked(nudge));
    }

    private String buildNudge(String lowerCommand) {
        String suggested = mapToGovernedTool(lowerCommand);
        return "Steering: the governed harness does not allow running git/gh through shell_exec. "
                + "Use the dedicated git pack tool instead — " + suggested + ". "
                + "Available git tools: git_clone(url), git_checkout(branch), git_commit(message), "
                + "git_push(), git_create_pr(title, body). Re-issue your action using that tool.";
    }

    private String mapToGovernedTool(String lowerCommand) {
        if (lowerCommand.contains("clone")) return "call git_clone with the repository url";
        if (lowerCommand.contains("commit")) return "call git_commit with a message";
        if (lowerCommand.contains("push")) return "call git_push";
        if (lowerCommand.contains("checkout") || lowerCommand.contains("switch") || lowerCommand.contains("branch"))
            return "call git_checkout with the branch name";
        if (lowerCommand.contains("pull request") || lowerCommand.contains("create pr") || lowerCommand.contains("pr create"))
            return "call git_create_pr with a title and body";
        return "call the matching git_* tool";
    }

    private String extractCommand(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return null;
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
            Object cmd = args.get("command");
            return cmd == null ? null : cmd.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
