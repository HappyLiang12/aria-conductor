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
        // The observed page: 300 removals and no qa_report.md entry. The report add
        // existed on the commit but fell off GitHub's 300-file page.
        List<ChangedFile> files = IntStream.range(0, 300)
                .mapToObj(i -> new ChangedFile("agent-control-tower/src/main/File" + i + ".java",
                        ChangeType.REMOVED))
                .toList();

        Optional<String> violation = guard.violation(new TreeDiff(
                "sdd/9f3d06d3", "451c4867", "984df552", files));

        assertThat(violation).isPresent();
        assertThat(violation.get())
                .contains("may be truncated")
                .contains("but not " + ReportStepTreeGuard.REPORT_PATH);
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
    void aReportLessPageBelowTheLimit_isNotJudged() {
        // One entry under the cap: no truncation, so the DEV head keeps its
        // previous behaviour even when it consists only of removals.
        List<ChangedFile> devRefactor = IntStream
                .range(0, ReportStepTreeGuard.GITHUB_FILE_LIST_LIMIT - 1)
                .mapToObj(i -> new ChangedFile("agent-control-tower/src/main/File" + i + ".java",
                        ChangeType.REMOVED))
                .toList();

        assertThat(guard.violation(new TreeDiff("sdd/chain-1", "parent", "dev-head", devRefactor)))
                .isEmpty();
    }

    @Test
    void aFullPageThatIncludesTheReport_failsClosedAsTruncated() {
        // A truncated page can also hide removals after qa_report.md. With no
        // observed removal the old rule accepted this page; the cap makes it
        // ambiguous, so it must fail closed too.
        List<ChangedFile> files = new java.util.ArrayList<>(IntStream
                .range(0, ReportStepTreeGuard.GITHUB_FILE_LIST_LIMIT - 1)
                .mapToObj(i -> new ChangedFile("agent-control-tower/src/main/File" + i + ".java",
                        ChangeType.MODIFIED))
                .toList());
        files.add(new ChangedFile(ReportStepTreeGuard.REPORT_PATH, ChangeType.ADDED));

        Optional<String> violation = guard.violation(
                new TreeDiff("sdd/chain-1", "parent", "truncated-head", files));

        assertThat(violation).isPresent();
        assertThat(violation.get())
                .contains("may be truncated")
                .contains("including " + ReportStepTreeGuard.REPORT_PATH);
    }

    @Test
    void noTreeToJudge_isAccepted() {
        assertThat(guard.violation(null)).isEmpty();
        assertThat(guard.violation(new TreeDiff("sdd/chain-1", "parent", "head", List.of()))).isEmpty();
    }
}
