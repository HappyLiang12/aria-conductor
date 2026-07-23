package io.aria.conductor.execution.harness;

import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionResult;
import io.aria.conductor.execution.pipeline.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSteeringGuardTest {

    private final ToolSteeringGuard guard = new ToolSteeringGuard();

    private HarnessProfile steeringOn() {
        return new HarnessProfile("weak", List.of("shell_exec"),
                new HarnessProfile.Steering(true),
                new HarnessProfile.SelfVerify(true, List.of("PUSH"), 200, null), 25, 16000);
    }

    @Test
    void interceptsShellExecGitClone() {
        Action a = new Action("shell_exec", ActionType.EXECUTE,
                "{\"command\":\"git clone https://x/y.git\"}", "tc-1");
        Optional<ActionResult> r = guard.intercept(a, steeringOn());
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(ActionResult.Status.BLOCKED);
        assertThat(r.get().error()).contains("git_clone");
    }

    @Test
    void interceptsShellExecGitPush() {
        Action a = new Action("shell_exec", ActionType.EXECUTE,
                "{\"command\":\"git push origin main\"}", "tc-2");
        Optional<ActionResult> r = guard.intercept(a, steeringOn());
        assertThat(r).isPresent();
        assertThat(r.get().error()).contains("git_push");
    }

    @Test
    void ignoresNonGitShellExec() {
        Action a = new Action("shell_exec", ActionType.EXECUTE, "{\"command\":\"ls -la\"}", "tc-3");
        assertThat(guard.intercept(a, steeringOn())).isEmpty();
    }

    @Test
    void ignoresWhenSteeringDisabled() {
        Action a = new Action("shell_exec", ActionType.EXECUTE, "{\"command\":\"git push\"}", "tc-4");
        assertThat(guard.intercept(a, HarnessProfile.defaults())).isEmpty();
    }

    @Test
    void ignoresNonShellExecTool() {
        Action a = new Action("git_clone", ActionType.WRITE, "{\"url\":\"https://x/y.git\"}", "tc-5");
        assertThat(guard.intercept(a, steeringOn())).isEmpty();
    }

    @Test
    void ignoresNullProfile() {
        Action a = new Action("shell_exec", ActionType.EXECUTE, "{\"command\":\"git clone x\"}", "tc-6");
        assertThat(guard.intercept(a, null)).isEmpty();
    }
}
