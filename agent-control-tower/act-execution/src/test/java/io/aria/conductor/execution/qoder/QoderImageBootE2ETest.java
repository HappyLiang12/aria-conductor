package io.aria.conductor.execution.qoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice A gate (Task A1): the pinned qoder-sandbox image must boot inside the local
 * OpenSandbox server and run the pinned Qoder CLI.
 *
 * <p>This is a real-stack test (real OpenSandbox server on podman, real sandbox container,
 * real Linux Qoder CLI artifact) — it is the proof that the pinned image is bootable and
 * that {@code qodercli} exists in it. It never touches credentials: only
 * {@code qodercli --version} is executed.
 *
 * <p>Gated by {@code -Dqoder.e2e.enabled=true} so the default unit/integration lanes skip it
 * (the local OpenSandbox server and the built image are prerequisites, not CI services):
 * <pre>
 * cd agent-control-tower
 * mvn verify -pl act-execution -Dit.test=QoderImageBootE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
 * </pre>
 * Add {@code clean} (or {@code -Dskip.unit.tests=true}) when the tree holds a stale
 * {@code act-execution/target/jacoco.exec}: {@code jacoco:check} is bound to the {@code test}
 * phase, is skipped only by {@code skip.unit.tests}, and fails on mismatched exec data before
 * Failsafe ever runs.
 *
 * <p>Evidence: {@code e2e/qoder/slice-a/01-boot.md} (image digest, CLI version, raw output).
 */
@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")
class QoderImageBootE2ETest {

    /**
     * Image built from {@code agent-control-tower/qoder-sandbox/Dockerfile}
     * (podman and the OpenSandbox server share one image store on this machine).
     */
    private static final String QODER_SANDBOX_IMAGE = "aria-conductor/qoder-sandbox:0.1";

    /** CLI version pinned in the Dockerfile (sha256-pinned official Linux artifact). */
    private static final String PINNED_CLI_VERSION = "1.1.41";

    /** Local OpenSandbox server: docker-compose service {@code opensandbox-server}, host port 8090. */
    private static final String SANDBOX_SERVER_URL = "http://localhost:8090";

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void pinnedImageBootsAndRunsPinnedQoderCli() {
        try (QoderSandboxHarness harness = QoderSandboxHarness.boot(SANDBOX_SERVER_URL, QODER_SANDBOX_IMAGE)) {
            String output = harness.run("qodercli --version 2>&1");
            // Raw evidence for e2e/qoder/slice-a/01-boot.md (captured in the Failsafe report).
            System.out.println("[A1] sandbox " + harness.sandboxId() + " image " + QODER_SANDBOX_IMAGE
                    + " `qodercli --version` => " + output.trim());
            assertThat(output)
                    .as("`qodercli --version` output inside sandbox %s (image %s)",
                            harness.sandboxId(), QODER_SANDBOX_IMAGE)
                    .containsPattern("\\b\\d+\\.\\d+\\.\\d+\\b")
                    .contains(PINNED_CLI_VERSION);
        }
    }
}
