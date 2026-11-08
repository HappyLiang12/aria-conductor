package io.aria.conductor.execution.listener;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * F19 tree guard for a report-only step (the QA step that commits
 * {@code qa_report.md}). The live QA commit 984df552 ("sdd qa report", parent
 * 451c4867) removed 300 files and left only the report on the chain branch, and the
 * chain accepted the step; a tree like that must fail the step instead.
 *
 * <p>The policy judges a tree diff between the step's parent commit and its head:
 * a report-only step may add or modify files, but it must not remove anything. Two
 * rules make that concrete:
 * <ul>
 *   <li>any removal outside {@link #REPORT_PATH} is a violation (the observed commit);</li>
 *   <li>more than {@link #MAX_REMOVALS} removals is a violation regardless of path,
 *       so a mass rewrite of the report history is not waved through;</li>
 *   <li>a page at GitHub's {@link #GITHUB_FILE_LIST_LIMIT}-file cap is a violation,
 *       because a truncated page can hide either the report or the removals.</li>
 * </ul>
 *
 * <p>Wiring: {@code GitBranchService.commitFileChanges} reads the pushed commit
 * ({@code GET /repos/{owner}/{repo}/commits/{sha}}: parents plus the changed
 * files), {@link GitTreeDiffSupplier} maps the chain branch's head commit onto a
 * {@link TreeDiff}, and {@code WorkflowAutoChainer} runs this policy on a QA PASS
 * before recording the review or advancing the chain. A violation fails the step.
 */
public final class ReportStepTreeGuard {

    /** The only file a report-only step exists to change. */
    public static final String REPORT_PATH = "qa_report.md";

    /** Removals tolerated for any path; the observed bad commit removed 300. */
    public static final int MAX_REMOVALS = 5;

    /**
     * GitHub returns at most this many files for one commit page. The supplier
     * ({@code GitBranchService.commitFileChanges}) reads a single page and cannot
     * tell a complete 300-file list from a truncated one, so the guard treats the
     * cap as unverifiable. Following the Link header in the supplier would change
     * this rule.
     */
    public static final int GITHUB_FILE_LIST_LIMIT = 300;

    /** Change classes a tree diff entry can carry. */
    public enum ChangeType { ADDED, MODIFIED, REMOVED }

    /** One changed path between the parent and head trees. */
    public record ChangedFile(String path, ChangeType type) {
        public ChangedFile {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Changed file path is required");
            }
            if (type == null) {
                throw new IllegalArgumentException("Changed file type is required: " + path);
            }
        }
    }

    /** The branch tree move a report-only step produced: parent commit to pushed head. */
    public record TreeDiff(String branchName, String parentSha, String headSha, List<ChangedFile> files) {
        public TreeDiff {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** Read side of the seam: {@link GitTreeDiffSupplier} supplies the real commit diff. */
    @FunctionalInterface
    public interface TreeDiffSupplier {
        /** @return the diff a step pushed, or empty when the branch could not be compared */
        Optional<TreeDiff> diffFor(String repoUrl, String branchName);
    }

    /**
     * @return an actionable failure reason when the tree diff proves the report-only
     *         step removed source files, or empty when the diff is acceptable
     */
    public Optional<String> violation(TreeDiff diff) {
        if (diff == null || diff.files().isEmpty()) {
            return Optional.empty();
        }
        // The branch head is the QA report commit only when that commit actually
        // touches the report. A PASS whose report capture did not push a commit
        // leaves the DEV head in place; judging that commit would fail a legitimate
        // PASS for removals the report step never made. No report move, no guard.
        boolean reportTouched = diff.files().stream()
                .anyMatch(file -> REPORT_PATH.equals(normalize(file.path())));
        if (diff.files().size() >= GITHUB_FILE_LIST_LIMIT) {
            // GitHub returns at most 300 files for one commit. The incident commit
            // removed 300 files and added the report; the page held the removals but
            // not qa_report.md, so the report-touched branch never ran. The same cap
            // can hide removals when the report is present. A full page is therefore
            // ambiguous in both directions and the guard fails closed.
            return Optional.of("QA report commit on branch '" + diff.branchName() + "' lists "
                    + diff.files().size() + " changed files "
                    + (reportTouched ? "including " : "but not ") + REPORT_PATH
                    + "; GitHub caps a commit's file list at " + GITHUB_FILE_LIST_LIMIT
                    + ", so this page may be truncated and its removals cannot be verified."
                    + " Restore any files the step removed and commit ONLY " + REPORT_PATH
                    + " with an explicit pathspec, then retry the step.");
        }
        if (!reportTouched) {
            return Optional.empty();
        }
        List<String> removals = diff.files().stream()
                .filter(file -> file.type() == ChangeType.REMOVED)
                .map(ChangedFile::path)
                .toList();
        if (removals.isEmpty()) {
            return Optional.empty();
        }

        List<String> outsideReport = removals.stream()
                .filter(path -> !REPORT_PATH.equals(normalize(path)))
                .toList();

        if (outsideReport.isEmpty() && removals.size() <= MAX_REMOVALS) {
            return Optional.empty();
        }

        StringBuilder reason = new StringBuilder("QA report commit on branch '")
                .append(diff.branchName()).append("' removed ").append(removals.size())
                .append(" file(s)");
        if (!outsideReport.isEmpty()) {
            reason.append("; ").append(outsideReport.size())
                    .append(" of them outside ").append(REPORT_PATH)
                    .append(" (first: ").append(sample(outsideReport)).append(')');
        }
        if (removals.size() > MAX_REMOVALS) {
            reason.append("; the limit is ").append(MAX_REMOVALS);
        }
        reason.append(". A report-only step must commit ONLY ").append(REPORT_PATH)
                .append(" with an explicit pathspec; restore the removed files and retry the step.");
        return Optional.of(reason.toString());
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String sample(List<String> paths) {
        int shown = Math.min(3, paths.size());
        String joined = String.join(", ", paths.subList(0, shown));
        return paths.size() > shown ? joined + ", ..." : joined;
    }
}
