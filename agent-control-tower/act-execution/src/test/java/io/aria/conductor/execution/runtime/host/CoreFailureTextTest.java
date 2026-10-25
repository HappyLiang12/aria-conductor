package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.common.model.RunErrorText;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator text of a failed turn is the only pointer to the run-owned core
 * log, and it is persisted through {@link RunErrorText#bound(String)}, which
 * keeps the first ~1,850 characters of the message.
 *
 * <p>Finding 3 of the review: the path was appended at the end, so any core
 * failure longer than the bound lost the path -- the one thing in the message an
 * operator needs to find the rest of the evidence. This test lives in the host
 * package because the path it protects is {@link RunCoreLog}'s.
 */
class CoreFailureTextTest {

    private static final UUID RUN = UUID.fromString("0f4c0f68-9c4a-4d3a-9b35-3d9c1d24a1c1");

    @Test
    void aLongCoreFailureStillNamesTheCoreLogPathAfterTheOperatorTextIsBounded() {
        String path = RunCoreLog.pathIn(Path.of("C:/runtime/runs/" + RUN), RUN).toString();
        String coreLog = " (core log: " + path + ")";
        String detail = "the bridge event stream failed: " + "x".repeat(2_400);

        String persisted = RunErrorText.bound(CoreExecutionService.failureText(RUN, detail, coreLog));

        assertThat(persisted)
                .hasSizeLessThanOrEqualTo(RunErrorText.MAX_PERSISTED_LENGTH)
                .as("the path must survive the bound, or the evidence the log holds is unreachable")
                .contains(coreLog)
                .contains("the bridge event stream failed")
                .doesNotContain("[truncated, ");
    }

    @Test
    void aShortCoreFailureIsUnchangedByTheReservation() {
        String path = RunCoreLog.pathIn(Path.of("C:/runtime/runs/" + RUN), RUN).toString();
        String coreLog = " (core log: " + path + ")";

        String persisted = RunErrorText.bound(
                CoreExecutionService.failureText(RUN, "the bridge event stream failed", coreLog));

        assertThat(persisted).isEqualTo("Run " + RUN + " failed: the bridge event stream failed" + coreLog);
    }
}
