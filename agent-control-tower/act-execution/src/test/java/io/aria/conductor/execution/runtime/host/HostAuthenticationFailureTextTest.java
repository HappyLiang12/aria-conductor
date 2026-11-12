package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.credential.CoreCredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31: the control-secret handshake failure used to name only the handshake
 * detail, while the run-owned core log carried the bridge's actual error
 * ({@code E_USAGE Unknown flag: --child-env-file}) the operator could not see.
 * The message now names that file -- the path only, never its contents.
 * <p>
 * T53 F3 (live bar): a rejected credential looked like a transport failure. The bridge exits on
 * {@code E_AUTH_REQUIRED} at {@code session/new}, so the control-secret dial only ever saw
 * {@code ConnectException} and the operator was told the run's secret did not authenticate. The
 * message now classifies the bridge error from that same run-owned log and says plainly that the
 * saved credential was rejected -- distinguishable from a transport failure by a stable marker.
 */
class HostAuthenticationFailureTextTest {

    private static final UUID RUN = UUID.fromString("8a2d5e70-1c3b-4d9a-9f21-7b6c5d4e3f10");

    /** The live bridge line from run 961868f4 (report-A F3), reproduced verbatim. */
    private static final String LIVE_CREDENTIAL_REJECTION =
            "[out] {\"type\":\"bridge.error\",\"code\":\"E_AUTH_REQUIRED\","
                    + "\"message\":\"Authentication required: Authentication is required.\","
                    + "\"rpcCode\":-32000,\"method\":\"session/new\"}\n";

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

    /**
     * The classification the live bar needed: the same run-owned log that carries
     * {@code E_AUTH_REQUIRED} at {@code session/new} makes the failure say that the saved
     * credential was rejected, under the stable marker a client can match. No log content is
     * quoted back -- the message names the classification, never the bridge's raw line.
     */
    @Test
    void classifiesACredentialRejectionFromTheRunOwnedCoreLog(@TempDir Path temp) throws IOException {
        Path coreLog = temp.resolve("core-" + RUN + ".log");
        Files.writeString(coreLog, LIVE_CREDENTIAL_REJECTION, StandardCharsets.UTF_8);

        String message = HostExecutionBackend.authenticationFailureMessage(
                RUN, "no authenticated answer within PT30S (last: ConnectException: null)", coreLog);

        assertThat(message)
                .as("the operator learns the cause, not just the transport symptom")
                .contains(CoreCredentialService.CREDENTIAL_REJECTED_MARKER)
                .contains("rejected the saved Qoder runtime credential")
                .contains("a credential failure, not a transport failure")
                .contains("PUT /api/v1/cores/qoder/credential");
        assertThat(message)
                .as("the log's own text is never echoed into the API surface")
                .doesNotContain("Authentication is required")
                .doesNotContain("rpcCode");
        assertThat(message)
                .as("the classification survives the 300-character failed-turn clip")
                .startsWith(CoreCredentialService.CREDENTIAL_REJECTED_MARKER)
                .hasSizeGreaterThan(300);
        assertThat(message.toLowerCase(java.util.Locale.ROOT))
                .as("the shipped dashboard matcher (errorActionFor) already routes this phrase to"
                        + " Configure > LLM Providers, so the action is reachable before the"
                        + " frontend lane matches the new marker")
                .contains("qoder runtime credential");
        assertThat(message)
                .as("still one bounded line")
                .doesNotContain("\n");
    }

    /**
     * The positive control for the test above: a run-owned log that carries a different bridge
     * error classifies as before. Absence of the marker here proves it is driven by the log's
     * content, not emitted for every authentication failure.
     */
    @Test
    void doesNotClaimACredentialRejectionForADifferentBridgeError(@TempDir Path temp) throws IOException {
        Path coreLog = temp.resolve("core-" + RUN + ".log");
        Files.writeString(coreLog,
                "[out] {\"type\":\"bridge.error\",\"code\":\"E_USAGE\","
                        + "\"message\":\"Unknown flag: --child-env-file\"}\n",
                StandardCharsets.UTF_8);

        String message = HostExecutionBackend.authenticationFailureMessage(
                RUN, "no authenticated answer within PT30S (last: ConnectException: null)", coreLog);

        assertThat(message)
                .as("a non-credential bridge error is not relabelled")
                .doesNotContain(CoreCredentialService.CREDENTIAL_REJECTED_MARKER)
                .doesNotContain("rejected the saved Qoder runtime credential")
                .contains("did not authenticate")
                .contains("run-owned core log at " + coreLog);
    }

    /** An unreadable or missing log is not evidence: the classification stays silent. */
    @Test
    void doesNotClaimACredentialRejectionWhenTheCoreLogCannotBeRead(@TempDir Path temp) {
        Path missing = temp.resolve("core-" + RUN + ".log");

        String message = HostExecutionBackend.authenticationFailureMessage(
                RUN, "no attempt was made", missing);

        assertThat(message).doesNotContain(CoreCredentialService.CREDENTIAL_REJECTED_MARKER);
    }
}
