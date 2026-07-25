package io.aria.conductor.execution.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour and security tests for {@link SandboxRunner}.
 *
 * <p>Per project decision the sandbox default is NONE: when no container runtime
 * is detected, {@code execute} must degrade to a failed {@link SandboxResult}
 * (never throw) so callers can fall back to plain handler execution.
 *
 * <p>The script validation gate fires BEFORE any process is spawned, so those
 * tests are OS-independent even when a runtime is forced (established pattern:
 * assert the pre-spawn gate only, see ShellExecHandlerTest). The runtime field
 * is pinned via ReflectionTestUtils so no test depends on whether docker/podman
 * is installed on the build machine.
 */
class SandboxRunnerTest {

    private SandboxRunner runnerWithRuntime(String runtime) {
        SandboxRunner runner = new SandboxRunner();
        ReflectionTestUtils.setField(runner, "containerRuntime", runtime);
        return runner;
    }

    // ---- degradation path (sandbox unavailable → handler fallback signal) ----

    @Test
    void execute_withoutContainerRuntime_returnsFailedResultInsteadOfThrowing() {
        SandboxRunner runner = runnerWithRuntime(null);

        SandboxResult result = runner.execute("echo hi", "sh", 256, "0.5", 1000);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("No container runtime available");
        assertThat(result.getOutput()).isNull();
    }

    @Test
    void isSandboxAvailable_reflectsDetectedRuntime() {
        assertThat(runnerWithRuntime(null).isSandboxAvailable()).isFalse();
        assertThat(runnerWithRuntime(null).getRuntime()).isNull();

        SandboxRunner dockerRunner = runnerWithRuntime("docker");
        assertThat(dockerRunner.isSandboxAvailable()).isTrue();
        assertThat(dockerRunner.getRuntime()).isEqualTo("docker");
    }

    // ---- script validation gate (fires before any process spawn) ----

    @ParameterizedTest(name = "blank script rejected: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void execute_rejectsBlankScript_beforeSpawningAnything(String script) {
        // Runtime is forced so we prove validation, not availability, is what rejects.
        SandboxRunner runner = runnerWithRuntime("docker");

        assertThatThrownBy(() -> runner.execute(script, "sh", 256, "0.5", 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Script must not be blank");
    }

    @ParameterizedTest(name = "non-printable/injection payload rejected: [{0}]")
    @ValueSource(strings = {
            "echo hi\u0000rm -rf /",          // NUL byte splice
            "echo safe\u001b[2J; rm -rf /",   // ANSI escape smuggling
            "curl evil.sh \u202e",            // RTL-override spoofing
            "echo \u00e9vil",                 // non-ASCII
            "echo hi\u0007"                   // bell control char
    })
    void validateScript_rejectsNonPrintableCharacters(String script) {
        assertThatThrownBy(() -> SandboxRunner.validateScript(script))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Script contains disallowed characters");
    }

    @Test
    void validateScript_acceptsPrintableAsciiWithNewlinesAndTabs() {
        // Multi-line shell scripts with tabs are the expected legitimate input.
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> SandboxRunner.validateScript("#!/bin/sh\necho hi\n\tls -la\r\n"))
                .doesNotThrowAnyException();
    }

    // ---- container command construction (isolation flags + result mapping inputs) ----

    @Test
    void buildRunCommand_appliesFullIsolationHardening() {
        String cmd = SandboxRunner.buildRunCommand(
                "docker", "/tmp/act-sandbox-1.sh", 512, "1.0", 30000, "/bin/sh");

        assertThat(cmd)
                .startsWith("docker run --rm")
                .contains("--memory=512m")
                .contains("--cpus=1.0")
                .contains("--network=none")          // no exfiltration channel
                .contains("--read-only")             // immutable rootfs
                .contains("--tmpfs /tmp:rw,noexec,nosuid,size=64m") // no exec from tmp
                .contains("-v /tmp/act-sandbox-1.sh:/script.sh:ro") // script is read-only
                .contains("--entrypoint /bin/sh")
                .endsWith("alpine:latest /script.sh");
    }

    @Test
    void buildRunCommand_normalizesWindowsPathsToForwardSlashes() {
        String cmd = SandboxRunner.buildRunCommand(
                "podman", "C:\\Temp\\act-sandbox-2.sh", 256, "0.5", 1000, "python3");

        assertThat(cmd)
                .startsWith("podman run")
                .contains("-v C:/Temp/act-sandbox-2.sh:/script.sh:ro")
                .doesNotContain("\\")
                .contains("--entrypoint python3");
    }
}
