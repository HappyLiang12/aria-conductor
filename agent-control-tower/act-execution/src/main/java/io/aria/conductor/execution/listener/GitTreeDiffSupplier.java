package io.aria.conductor.execution.listener;

import io.aria.conductor.execution.git.GitBranchService;
import io.aria.conductor.execution.git.GitBranchService.CommitFile;
import io.aria.conductor.execution.git.GitBranchService.CommitFileChanges;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangeType;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangedFile;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.TreeDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * F19 wiring: supplies {@link ReportStepTreeGuard} with the tree move a step pushed
 * on its SDD branch.
 *
 * <p>The diff is the branch HEAD commit against its first parent, read through
 * {@link GitBranchService#branchHeadSha} and {@link GitBranchService#commitFileChanges}:
 * the QA step's report commit is the HEAD the run pushed, and its parent is the tree
 * the chain expected to keep. Every failure to resolve the branch, its head or the
 * commit is {@link Optional#empty()} at debug level, so a non-SDD chain (no repository)
 * and a transient GitHub error both leave the step unguarded rather than failing it.
 *
 * <p>GitHub status mapping: {@code removed} is the only removal; a {@code renamed}
 * entry names the new path (so it is a modification there, and the old path only
 * counts as removed if GitHub reports it), a {@code copied} entry adds the new path,
 * and {@code added}/{@code modified}/{@code changed} map directly.
 */
@Slf4j
@Component
public class GitTreeDiffSupplier implements ReportStepTreeGuard.TreeDiffSupplier {

    private final GitBranchService gitBranchService;

    public GitTreeDiffSupplier(GitBranchService gitBranchService) {
        this.gitBranchService = gitBranchService;
    }

    @Override
    public Optional<TreeDiff> diffFor(String repoUrl, String branchName) {
        if (repoUrl == null || repoUrl.isBlank() || branchName == null || branchName.isBlank()) {
            log.debug("No repository/branch to compare; skipping the report-step tree guard");
            return Optional.empty();
        }
        try {
            return readDiff(repoUrl, branchName);
        } catch (RuntimeException e) {
            // No credential, a 401/5xx ref read, an interrupted request: an
            // infrastructure condition, never a reason to fail the QA step — but
            // the operator must see that this PASS went unguarded.
            // The repository URL is not logged: it may carry userinfo (a token).
            log.warn("Could not read the tree of branch {}; the report-step tree guard is"
                    + " skipped for this pass: {}", branchName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TreeDiff> readDiff(String repoUrl, String branchName) {
        Optional<String> headSha = gitBranchService.branchHeadSha(repoUrl, branchName);
        if (headSha.isEmpty() || headSha.get().isBlank()) {
            log.debug("Branch {} has no head to compare; skipping the tree guard", branchName);
            return Optional.empty();
        }
        Optional<CommitFileChanges> commit = gitBranchService.commitFileChanges(repoUrl, headSha.get());
        if (commit.isEmpty()) {
            log.debug("Could not read commit {} on {}; skipping the tree guard",
                    headSha.get(), branchName);
            return Optional.empty();
        }
        if (commit.get().parentShas().isEmpty()) {
            log.debug("Commit {} on {} has no parent (root commit); nothing to compare",
                    headSha.get(), branchName);
            return Optional.empty();
        }
        List<ChangedFile> files = commit.get().files().stream()
                .map(file -> new ChangedFile(file.path(), map(file.status())))
                .toList();
        return Optional.of(new TreeDiff(branchName, commit.get().parentShas().get(0),
                headSha.get(), files));
    }

    /** GitHub commit status to the guard's change class. Only {@code removed} removes. */
    static ChangeType map(CommitFile.Status status) {
        return switch (status) {
            case ADDED, COPIED -> ChangeType.ADDED;
            case REMOVED -> ChangeType.REMOVED;
            // A rename changes the tree at its new path; it is not a removal of it.
            case MODIFIED, RENAMED, CHANGED, UNKNOWN -> ChangeType.MODIFIED;
        };
    }
}
