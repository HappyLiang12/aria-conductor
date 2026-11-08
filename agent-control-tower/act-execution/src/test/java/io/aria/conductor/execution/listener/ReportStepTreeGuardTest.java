package io.aria.conductor.execution.listener;

import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangeType;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangedFile;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.TreeDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F19: the QA report commit 984df552 ("sdd qa report", parent 451c4867) removed 300
 * files and left only qa_report.md on the chain branch; the chain accepted the step.
 * A report-only step may add or modify the report, and must not remove source files.
 */
class ReportStepTreeGuardTest {

    private final ReportStepTreeGuard guard = new ReportStepTreeGuard();

    @Test
    void reportOnlyCommit_isAccepted() {
        TreeDiff diff = new TreeDiff("sdd/chain-1", "451c4867", "984df552", List.of(
                new ChangedFile("qa_report.md", ChangeType.ADDED)));

        assertThat(guard.violation(diff)).isEmpty();
    }

    @Test
    void commitThatRemovesTheRepositoryTree_isRejected() {
        // The observed commit: 300 removals, none of them the report.
        List<ChangedFile> files = new java.util.ArrayList<>(IntStream.range(0, 300)
                .mapToObj(i -> new ChangedFile("agent-control-tower/src/main/File" + i + ".java",
                        ChangeType.REMOVED))
                .toList());
        files.add(new ChangedFile("qa_report.md", ChangeType.ADDED));

        Optional<String> violation = guard.violation(new TreeDiff(
                "sdd/9f3d06d3", "451c4867", "984df552", files));

        assertThat(violation).isPresent();
        assertThat(violation.get())
                .contains("removed 300")
                .contains("outside qa_report.md")
                .contains("File0.java");
    }

    @Test
    void aSingleRemovedSourceFile_isRejected() {
        TreeDiff diff = new TreeDiff("sdd/chain-1", "parent", "head", List.of(
                new ChangedFile("qa_report.md", ChangeType.MODIFIED),
                new ChangedFile("agent-control-tower/act-app/pom.xml", ChangeType.REMOVED)));

        Optional<String> violation = guard.violation(diff);

        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("act-app/pom.xml");
    }

    @Test
    void removalsBeyondTheCap_areRejectedEvenWhenEveryPathIsTheReport() {
        List<ChangedFile> files = IntStream.range(0, ReportStepTreeGuard.MAX_REMOVALS + 1)
                .mapToObj(i -> new ChangedFile(ReportStepTreeGuard.REPORT_PATH, ChangeType.REMOVED))
                .toList();

        Optional<String> violation = guard.violation(
                new TreeDiff("sdd/chain-1", "parent", "head", files));

        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("limit is " + ReportStepTreeGuard.MAX_REMOVALS);
    }

    @Test
    void modificationsOnly_areAccepted() {
        TreeDiff diff = new TreeDiff("sdd/chain-1", "parent", "head", List.of(
                new ChangedFile("qa_report.md", ChangeType.MODIFIED),
                new ChangedFile("spec/spec.md", ChangeType.MODIFIED)));

        assertThat(guard.violation(diff)).isEmpty();
    }

    @Test
    void aHeadCommitThatDoesNotTouchTheReport_isNotJudged() {
        // A PASS whose report capture did not push a commit leaves the DEV head on
        // the branch. Its refactor removals are not the report step's tree move.
        List<ChangedFile> devRefactor = IntStream.range(0, ReportStepTreeGuard.MAX_REMOVALS + 1)
                .mapToObj(i -> new ChangedFile("agent-control-tower/src/main/File" + i + ".java",
                        ChangeType.REMOVED))
                .toList();

        assertThat(guard.violation(new TreeDiff("sdd/chain-1", "parent", "dev-head", devRefactor)))
                .isEmpty();
    }

    @Test
    void noTreeToJudge_isAccepted() {
        assertThat(guard.violation(null)).isEmpty();
        assertThat(guard.violation(new TreeDiff("sdd/chain-1", "parent", "head", List.of()))).isEmpty();
    }
}
