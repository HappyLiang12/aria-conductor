package io.aria.conductor.knowledge.selfimprove;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sandbox tests gracefully skip when the chosen interpreter (python) is
 * unavailable on PATH so the build stays green on bare CI agents.
 */
class SandboxExecutorTest {

    private final SandboxExecutor executor = new SandboxExecutor();

    @Test
    void executeSimpleScript_capturesStdout() {
        assumeTrue(pythonAvailable(), "python interpreter required");
        var r = executor.execute("print('hello-sandbox')", "python", Map.of());
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.stdout().trim()).isEqualTo("hello-sandbox");
        assertThat(r.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void scriptError_returnsNonZeroExitAndStderr() {
        assumeTrue(pythonAvailable(), "python interpreter required");
        var r = executor.execute(
                "import sys\nsys.stderr.write('boom\\n')\nsys.exit(7)\n",
                "python", Map.of());
        assertThat(r.exitCode()).isEqualTo(7);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.stderr()).contains("boom");
        assertThat(r.isSuccess()).isFalse();
    }

    @Test
    void runawayScript_isKilledByTimeout() {
        assumeTrue(pythonAvailable(), "python interpreter required");
        executor.setTimeoutSeconds(1);
        try {
            var r = executor.execute(
                    "import time\nwhile True:\n    time.sleep(0.1)\n",
                    "python", Map.of());
            assertThat(r.timedOut()).isTrue();
            assertThat(r.exitCode()).isNotEqualTo(0);
        } finally {
            executor.setTimeoutSeconds(SandboxExecutor.TIMEOUT_SECONDS);
        }
    }

    @Test
    void output_exceedingLimit_isTruncated() {
        assumeTrue(pythonAvailable(), "python interpreter required");
        // Print just over MAX_OUTPUT_BYTES so the drain truncates.
        long target = SandboxExecutor.MAX_OUTPUT_BYTES + 50_000L;
        var r = executor.execute(
                "import sys\nsys.stdout.write('a' * " + target + ")\n",
                "python", Map.of());
        assertThat(r.exitCode()).isEqualTo(0);
        // Captured length must respect the cap (with a small overhead for
        // the truncation marker appended by the executor).
        assertThat((long) r.stdout().length())
                .isLessThanOrEqualTo(SandboxExecutor.MAX_OUTPUT_BYTES + 100L);
    }

    @Test
    void unsupportedLanguage_isReportedNotThrown() {
        var r = executor.execute("anything", "cobol", Map.of());
        // Implementation rejects unsupported languages internally and returns
        // a failure result.
        assertThat(r.exitCode()).isEqualTo(-1);
    }

    private boolean pythonAvailable() {
        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] cmd = isWin ? new String[]{"python", "--version"}
                             : new String[]{"python3", "--version"};
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
