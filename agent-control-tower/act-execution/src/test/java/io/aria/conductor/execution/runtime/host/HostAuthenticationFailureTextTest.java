package io.aria.conductor.execution.runtime.host;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31: the control-secret handshake failure used to name only the handshake
 * detail, while the run-owned core log carried the bridge's actual error
 * ({@code E_USAGE Unknown flag: --child-env-file}) the operator could not see.
 * The message now names that file -- the path only, never its contents.
 */
class HostAuthenticationFailureTextTest {

    private static final UUID RUN = UUID.fromString("8a2d5e70-1c3b-4d9a-9f21-7b6c5d4e3f10");

    @Test
    void namesTheRunOwnedCoreLogAndKeepsTheExistingDetail() {
        Path coreLog = Path.of("C:/runs/runtime/logs/core-" + RUN + ".log");

        String message = HostExecutionBackend.authenticationFailureMessage(RUN, "status 401", coreLog);

        assertThat(message)
                .as("the existing handshake detail stays the primary explanation")
                .contains("did not authenticate")
                .contains("status 401");
        assertThat(message)
                .as("the operator is pointed at the run-owned evidence")
                .contains("run-owned core log at " + coreLog);
        assertThat(message)
                .as("a failure text is one bounded line, not a pasted log")
                .doesNotContain("\n")
                .hasSizeLessThan(512)
                .endsWith(coreLog + ".");
    }

    @Test
    void omitsThePointerWhenNoCoreLogWasOpened() {
        String message = HostExecutionBackend.authenticationFailureMessage(
                RUN, "no attempt was made", null);

        assertThat(message)
                .contains("did not authenticate")
                .doesNotContain("run-owned core log")
                .doesNotContain("null");
    }
}
