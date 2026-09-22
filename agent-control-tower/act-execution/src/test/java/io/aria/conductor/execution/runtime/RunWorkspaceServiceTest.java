package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunWorkspaceLease;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.repository.RunWorkspaceLeaseRepository;
import io.aria.conductor.execution.tool.WorkspaceManager;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Task 6 behaviour: canonical workspace leases (spec 5.2), overlap rejection for
 * Direct writers, managed worktrees that preserve the source repository, retained
 * artifacts captured only after a verified stop proof, and ownership-aware
 * cleanup that never removes user repositories or retained worktrees.
 */
class RunWorkspaceServiceTest extends DataJpaTestBase {

    @Autowired
    private RunWorkspaceLeaseRepository leases;

    @TempDir
    Path temp;

    private Path runtimeRoot;
    private Path resultRoot;

    @BeforeEach
    void setUpDirectories() throws IOException {
        runtimeRoot = Files.createDirectories(temp.resolve("runtime-root"));
        resultRoot = Files.createDirectories(temp.resolve("result-root"));
    }

    // ---------------------------------------------------------------- overlap

    @Test
    void parentAndChildDirectoriesConflict() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path child = Files.createDirectories(repo.resolve("src"));
        Path other = Files.createDirectories(temp.resolve("other"));
        assertThat(WorkspacePaths.overlaps(repo, child)).isEqualTo(true);
        assertThat(WorkspacePaths.overlaps(repo, other)).isEqualTo(false);
    }

    @Test
    void directoryAliasOverlapsItsTarget() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo-alias-target"));
        Files.createDirectories(repo.resolve("src"));
        Path alias = temp.resolve("repo-alias");
        createDirectoryAlias(alias, repo);

        assertThat(alias.toRealPath()).isEqualTo(repo.toRealPath());
        assertThat(WorkspacePaths.overlaps(repo, alias)).isEqualTo(true);

        WorkspaceLease first = service().acquire(hostSpec(UUID.randomUUID(), WorkspaceMode.DIRECT, repo.toString(), null));
        UUID secondRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(
                hostSpec(secondRun, WorkspaceMode.DIRECT, alias.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace conflict: " + repo.toRealPath()
                        + " overlaps active lease " + first.leaseId() + " (run " + first.runId() + ")");
        assertThat(leases.findByRunId(secondRun)).isEmpty();
    }

    // ----------------------------------------------------------- direct leases

    @Test
    void directAcquireRequiresExplicitExistingDirectory() {
        UUID missingPathRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(missingPathRun, WorkspaceMode.DIRECT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct workspace requires an explicitly selected directory");
        UUID blankPathRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(blankPathRun, WorkspaceMode.DIRECT, "   ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct workspace requires an explicitly selected directory");
        Path missing = temp.resolve("missing-directory");
        UUID absentRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(absentRun, WorkspaceMode.DIRECT, missing.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct workspace directory does not exist: " + missing);

        assertThat(leases.findByRunId(missingPathRun)).isEmpty();
        assertThat(leases.findByRunId(blankPathRun)).isEmpty();
        assertThat(leases.findByRunId(absentRun)).isEmpty();
    }

    @Test
    void secondWriterOnSameOrChildTreeGetsExactConflictAndFirstLeaseIsRetained() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("two-writer-repo"));
        Path child = Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("keep.txt"), "keep\n");

        UUID firstRun = UUID.randomUUID();
        WorkspaceLease first = service().acquire(hostSpec(firstRun, WorkspaceMode.DIRECT, repo.toString(), null));
        assertThat(first.kind()).isEqualTo(WorkspaceKind.DIRECT);
        assertThat(first.localRoot()).isEqualTo(repo.toRealPath());

        UUID sameRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(sameRun, WorkspaceMode.DIRECT, repo.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace conflict: " + repo.toRealPath()
                        + " overlaps active lease " + first.leaseId() + " (run " + firstRun + ")");
        UUID childRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(childRun, WorkspaceMode.DIRECT, child.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace conflict: " + child.toRealPath()
                        + " overlaps active lease " + first.leaseId() + " (run " + firstRun + ")");

        RunWorkspaceLease firstRow = leases.findFirstByRunIdOrderByAcquiredAtDesc(firstRun).orElseThrow();
        assertThat(firstRow.getLeaseId()).isEqualTo(first.leaseId());
        assertThat(firstRow.getState()).isEqualTo(RunWorkspaceLease.State.ACTIVE);
        assertThat(leases.findByRunId(sameRun)).isEmpty();
        assertThat(leases.findByRunId(childRun)).isEmpty();

        service().release(first, new StopProof(firstRun, true));
        WorkspaceLease second = service().acquire(hostSpec(sameRun, WorkspaceMode.DIRECT, repo.toString(), null));
        assertThat(second.leaseId()).isNotEqualTo(first.leaseId());
        assertThat(Files.readString(repo.resolve("keep.txt"))).isEqualTo("keep\n");
    }

    @Test
    void persistedActiveLeaseBlocksNewWriterUntilReleased() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("pre-restart-repo"));
        UUID preRestartRun = UUID.randomUUID();
        UUID preRestartLeaseId = UUID.randomUUID();
        leases.save(RunWorkspaceLease.builder()
                .leaseId(preRestartLeaseId)
                .runId(preRestartRun)
                .kind(WorkspaceKind.DIRECT)
                .state(RunWorkspaceLease.State.ACTIVE)
                .localRoot(dir.toRealPath().toString())
                .sourceRoot(dir.toRealPath().toString())
                .runtimeRoot(runtimeRoot.resolve("runs").resolve(preRestartRun.toString()).toString())
                .retained(true)
                .acquiredAt(Instant.now())
                .build());

        UUID newRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(newRun, WorkspaceMode.DIRECT, dir.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace conflict: " + dir.toRealPath()
                        + " overlaps active lease " + preRestartLeaseId + " (run " + preRestartRun + ")");
        assertThat(leases.findByRunId(newRun)).isEmpty();

        RunWorkspaceLease stored = leases.findById(preRestartLeaseId).orElseThrow();
        stored.setState(RunWorkspaceLease.State.RELEASED);
        stored.setReleasedAt(Instant.now());
        leases.save(stored);

        WorkspaceLease admitted = service().acquire(hostSpec(newRun, WorkspaceMode.DIRECT, dir.toString(), null));
        assertThat(admitted.kind()).isEqualTo(WorkspaceKind.DIRECT);
        assertThat(admitted.localRoot()).isEqualTo(dir.toRealPath());
    }

    // ---------------------------------------------------------- scratch/sandbox

    @Test
    void scratchAcquireCreatesPlatformOwnedSweepableWorkspace() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, null, null, null));

        assertThat(lease.kind()).isEqualTo(WorkspaceKind.SCRATCH);
        assertThat(lease.sourceRoot()).isNull();
        assertThat(lease.localRoot()).isEqualTo(runtimeRoot.resolve("scratch").resolve(runId.toString()).toRealPath());
        assertThat(lease.runtimeRoot()).isEqualTo(runtimeRoot.resolve("runs").resolve(runId.toString()).toRealPath().toString());
        assertThat(lease.baseCommit()).isNull();

        RunWorkspaceLease row = leases.findFirstByRunIdOrderByAcquiredAtDesc(runId).orElseThrow();
        assertThat(row.getKind()).isEqualTo(WorkspaceKind.SCRATCH);
        assertThat(row.getState()).isEqualTo(RunWorkspaceLease.State.ACTIVE);
        assertThat(row.isRetained()).isFalse();

        service().release(lease, new StopProof(runId, true));
        RunWorkspaceLease released = leases.findById(lease.leaseId()).orElseThrow();
        assertThat(released.getState()).isEqualTo(RunWorkspaceLease.State.RELEASED);
        assertThat(released.getReleasedAt()).isNotNull();
        assertThat(released.isRetained()).isFalse();
    }

    @Test
    void sandboxLeaseRecordsSnapshotBindingWithoutHostExclusion() throws Exception {
        Path source = Files.createDirectories(temp.resolve("sandbox-source"));
        Files.writeString(source.resolve("data.txt"), "snapshot-source\n");

        UUID firstRun = UUID.randomUUID();
        ExecutionSpec firstSpec = new ExecutionSpec(firstRun, UUID.randomUUID(), "opencode",
                ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, source.toString(), null),
                null, "rev-1", Instant.now().plusSeconds(3600));
        WorkspaceLease first = service().acquire(firstSpec);

        assertThat(first.kind()).isEqualTo(WorkspaceKind.SANDBOX_SNAPSHOT);
        assertThat(first.sourceRoot()).isEqualTo(source.toRealPath());
        assertThat(first.localRoot()).isEqualTo(runtimeRoot.resolve("sandbox").resolve(firstRun.toString()).toRealPath());
        assertThat(Files.readString(source.resolve("data.txt"))).isEqualTo("snapshot-source\n");

        UUID secondRun = UUID.randomUUID();
        ExecutionSpec secondSpec = new ExecutionSpec(secondRun, UUID.randomUUID(), "opencode",
                ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, source.toString(), null),
                null, "rev-1", Instant.now().plusSeconds(3600));
        WorkspaceLease second = service().acquire(secondSpec);
        assertThat(second.kind()).isEqualTo(WorkspaceKind.SANDBOX_SNAPSHOT);
        assertThat(second.leaseId()).isNotEqualTo(first.leaseId());
        assertThat(Files.readString(source.resolve("data.txt"))).isEqualTo("snapshot-source\n");
    }

    // --------------------------------------------------------------- worktrees

    @Test
    void failedWorktreeCreationDoesNotFallBackToDirect() throws Exception {
        UUID plainRun = UUID.randomUUID();
        Path plain = Files.createDirectories(temp.resolve("not-a-repository"));
        assertThatThrownBy(() -> service().acquire(hostSpec(plainRun, WorkspaceMode.WORKTREE, plain.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Worktree workspace requires a git repository: " + plain);
        assertThat(leases.findByRunId(plainRun)).isEmpty();
        assertThat(Files.notExists(runtimeRoot.resolve("worktrees").resolve(plainRun.toString()))).isTrue();

        Path repo = temp.resolve("bad-ref-repo");
        initDisposableRepo(repo);
        UUID badRefRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(badRefRun, WorkspaceMode.WORKTREE, repo.toString(), "no-such-ref")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot resolve worktree base ref 'no-such-ref' in " + repo);
        assertThat(leases.findByRunId(badRefRun)).isEmpty();
        assertThat(Files.notExists(runtimeRoot.resolve("worktrees").resolve(badRefRun.toString()))).isTrue();
    }

    @Test
    void worktreeCreationResolvesRefAndPreservesSourceIndexAndDirtyBytes() throws Exception {
        Path repo = temp.resolve("worktree-source-repo");
        initDisposableRepo(repo);
        git(repo, "branch", "feature-branch");
        Files.writeString(repo.resolve("second.txt"), "second-commit\n");
        git(repo, "add", "second.txt");
        git(repo, "commit", "-q", "-m", "second");
        Files.writeString(repo.resolve("tracked.txt"), "dirty-edit\n");
        Files.writeString(repo.resolve("staged.txt"), "staged-bytes\n");
        git(repo, "add", "staged.txt");

        byte[] indexBefore = Files.readAllBytes(repo.resolve(".git/index"));
        String statusBefore = git(repo, "status", "--porcelain");
        String head = git(repo, "rev-parse", "HEAD");

        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(
                hostSpec(runId, WorkspaceMode.WORKTREE, repo.toString(), "feature-branch"));

        assertThat(lease.kind()).isEqualTo(WorkspaceKind.WORKTREE);
        assertThat(lease.baseCommit()).isEqualTo(git(repo, "rev-parse", "feature-branch"));
        assertThat(lease.baseCommit()).hasSize(40);
        assertThat(lease.baseCommit()).isNotEqualTo(head);
        assertThat(lease.sourceRoot()).isEqualTo(repo.toRealPath());
        assertThat(lease.localRoot()).isEqualTo(runtimeRoot.resolve("worktrees").resolve(runId.toString()).toRealPath());
        assertThat(lease.localRoot()).isNotEqualTo(lease.sourceRoot());

        // The managed worktree holds exactly the resolved commit, not the uncommitted edits.
        assertThat(Files.readString(lease.localRoot().resolve("tracked.txt"))).isEqualTo("v1\n");
        assertThat(Files.notExists(lease.localRoot().resolve("second.txt"))).isTrue();
        assertThat(git(lease.localRoot(), "status", "--porcelain")).isEmpty();

        // The source worktree keeps its exact index bytes and dirty file bytes.
        assertThat(Files.readAllBytes(repo.resolve(".git/index"))).isEqualTo(indexBefore);
        assertThat(Files.readString(repo.resolve("tracked.txt"))).isEqualTo("dirty-edit\n");
        assertThat(Files.readString(repo.resolve("staged.txt"))).isEqualTo("staged-bytes\n");
        assertThat(git(repo, "status", "--porcelain")).isEqualTo(statusBefore);

        RunWorkspaceLease row = leases.findFirstByRunIdOrderByAcquiredAtDesc(runId).orElseThrow();
        assertThat(row.getKind()).isEqualTo(WorkspaceKind.WORKTREE);
        assertThat(row.getState()).isEqualTo(RunWorkspaceLease.State.ACTIVE);
        assertThat(row.isRetained()).isTrue();
        assertThat(row.getBaseCommit()).isEqualTo(lease.baseCommit());
    }

    @Test
    void worktreeBaseRefCannotBeParsedAsAGitOption() throws Exception {
        Path repo = temp.resolve("option-ref-repo");
        initDisposableRepo(repo);
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(runId, WorkspaceMode.WORKTREE, repo.toString(), "--all")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot resolve worktree base ref '--all' in " + repo);
        assertThat(leases.findByRunId(runId)).isEmpty();
        assertThat(Files.notExists(runtimeRoot.resolve("worktrees").resolve(runId.toString()))).isTrue();
    }

    @Test
    void sha256RepositoryCommitIsAcceptedForWorktrees() throws Exception {
        // Git 2.24 (this machine) cannot create a SHA-256 repository, so the controlled
        // stand-in speaks exactly what git speaks in one: rev-parse --verify emits a 64-hex
        // commit id, worktree add receives that id as a discrete argv element, and every
        // invocation is logged so the argv-only call shape stays observable.
        Path repo = Files.createDirectories(temp.resolve("sha256-worktree-repo"));
        String commit = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Path argvLog = temp.resolve("sha256-git-argv.log");
        Path shim = writeGitShim(windowsShimSha256RepoBody(argvLog, commit), posixShimSha256RepoBody(argvLog, commit));
        UUID runId = UUID.randomUUID();

        WorkspaceLease lease = serviceWithGit(gitShimCommand(shim), Duration.ofSeconds(20))
                .acquire(hostSpec(runId, WorkspaceMode.WORKTREE, repo.toString(), null));

        assertThat(lease.kind()).isEqualTo(WorkspaceKind.WORKTREE);
        assertThat(lease.baseCommit()).isEqualTo(commit);
        assertThat(lease.baseCommit()).hasSize(64);
        Path worktree = runtimeRoot.toRealPath().resolve("worktrees").resolve(runId.toString());
        assertThat(lease.localRoot()).isEqualTo(worktree);
        assertThat(Files.isDirectory(lease.localRoot())).isTrue();

        // argv-only: the 64-hex commit travelled to git as its own argument, never interpolated.
        // (stripTrailing: cmd's `echo %*` leaves one trailing space before the redirect.)
        List<String> argvLines = Files.readAllLines(argvLog).stream().map(String::stripTrailing).toList();
        assertThat(argvLines).contains("-C " + repo + " worktree add --detach " + worktree + " " + commit);
        assertThat(argvLines).anySatisfy(line -> assertThat(line).contains("rev-parse --verify --quiet HEAD"));

        // The lease row persists the full 64-hex commit (the base_commit column is 64 wide).
        RunWorkspaceLease row = leases.findFirstByRunIdOrderByAcquiredAtDesc(runId).orElseThrow();
        assertThat(row.getKind()).isEqualTo(WorkspaceKind.WORKTREE);
        assertThat(row.getState()).isEqualTo(RunWorkspaceLease.State.ACTIVE);
        assertThat(row.getBaseCommit()).isEqualTo(commit);
    }

    @Test
    void twoWorktreesOfOneRepositoryAreAdmittedConcurrently() throws Exception {
        Path repo = temp.resolve("shared-worktree-repo");
        initDisposableRepo(repo);
        String head = git(repo, "rev-parse", "HEAD");

        UUID firstRun = UUID.randomUUID();
        WorkspaceLease first = service().acquire(hostSpec(firstRun, WorkspaceMode.WORKTREE, repo.toString(), null));

        // Direct exclusivity on the same canonical tree still holds while a worktree is active:
        // the worktree's source repository is not a second platform-owned tree.
        UUID directRun = UUID.randomUUID();
        assertThatThrownBy(() -> service().acquire(hostSpec(directRun, WorkspaceMode.DIRECT, repo.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace conflict: " + repo.toRealPath()
                        + " overlaps active lease " + first.leaseId() + " (run " + firstRun + ")");
        assertThat(leases.findByRunId(directRun)).isEmpty();

        // Two worktrees of one repository are the default coding workflow: each manages a
        // disjoint platform-owned tree, and common-git-directory mutations are serialized
        // by the platform-owned common-git lock instead of a workspace exclusion.
        UUID secondRun = UUID.randomUUID();
        WorkspaceLease second = service().acquire(hostSpec(secondRun, WorkspaceMode.WORKTREE, repo.toString(), null));

        assertThat(second.kind()).isEqualTo(WorkspaceKind.WORKTREE);
        assertThat(second.leaseId()).isNotEqualTo(first.leaseId());
        assertThat(second.localRoot())
                .isEqualTo(runtimeRoot.resolve("worktrees").resolve(secondRun.toString()).toRealPath());
        assertThat(second.localRoot()).isNotEqualTo(first.localRoot());
        assertThat(first.baseCommit()).isEqualTo(head);
        assertThat(second.baseCommit()).isEqualTo(head);
        assertThat(Files.readString(first.localRoot().resolve("tracked.txt"))).isEqualTo("v1\n");
        assertThat(Files.readString(second.localRoot().resolve("tracked.txt"))).isEqualTo("v1\n");
        assertThat(git(repo, "status", "--porcelain")).isEmpty();
        assertThat(leases.findByRunId(firstRun)).hasSize(1);
        assertThat(leases.findByRunId(secondRun)).hasSize(1);
    }

    @Test
    void failedWorktreeCleanupRefusesToDeleteThroughAJunctionAtTheWorktreeParent() throws Exception {
        Path repo = temp.resolve("junction-worktree-parent-repo");
        initDisposableRepo(repo);

        // The platform's worktree parent is replaced by a junction onto a tree the platform
        // never owned; the failing git stand-in creates the run directory through it, as a
        // partial worktree, and then fails the add.
        Path foreignTree = Files.createDirectories(temp.resolve("foreign-worktree-tree"));
        Path worktreesParent = runtimeRoot.resolve("worktrees");
        createDirectoryAlias(worktreesParent, foreignTree);
        UUID runId = UUID.randomUUID();
        Path foreignRunDir = foreignTree.resolve(runId.toString());

        try {
            Path shim = writeGitShim(windowsShimFailingWorktreeAddBody(), posixShimFailingWorktreeAddBody());
            List<String> shimCommand = gitShimCommand(shim);
            Throwable failure = catchThrowable(() -> serviceWithGit(shimCommand, Duration.ofSeconds(20))
                    .acquire(hostSpec(runId, WorkspaceMode.WORKTREE, repo.toString(), null)));

            Path canonicalRuntimeRoot = runtimeRoot.toRealPath();
            assertThat(failure).isInstanceOf(UncheckedIOException.class);
            assertThat(failure).hasRootCauseMessage("Refusing to delete "
                    + canonicalRuntimeRoot.resolve("worktrees").resolve(runId.toString())
                    + ": it is not reached through canonical parents from " + canonicalRuntimeRoot
                    + " (a link above the deletion root would redirect the deletion outside the owned tree)");
            // Nothing outside the junction target was deleted: the partial worktree survives.
            assertThat(Files.exists(foreignRunDir)).isTrue();
            assertThat(Files.readString(foreignRunDir.resolve("partial.txt"))).contains("partial");
            assertThat(leases.findByRunId(runId)).isEmpty();
        } finally {
            Files.deleteIfExists(worktreesParent);
        }
    }

    // ----------------------------------------------------------------- release

    @Test
    void releaseRequiresMatchingVerifiedStopProofAndRetentionIsSeparate() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("release-repo"));
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));
        UUID otherRun = UUID.randomUUID();

        assertThatThrownBy(() -> service().release(lease, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace release requires a verified stop proof for run " + runId);
        assertThatThrownBy(() -> service().release(lease, new StopProof(otherRun, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stop proof run " + otherRun + " does not match workspace lease run " + runId);
        assertThatThrownBy(() -> service().release(lease, new StopProof(runId, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stop proof does not verify that all writers stopped for run " + runId);

        RunWorkspaceLease unreleased = leases.findById(lease.leaseId()).orElseThrow();
        assertThat(unreleased.getState()).isEqualTo(RunWorkspaceLease.State.ACTIVE);
        assertThat(unreleased.getReleasedAt()).isNull();

        service().release(lease, new StopProof(runId, true));
        RunWorkspaceLease released = leases.findById(lease.leaseId()).orElseThrow();
        assertThat(released.getState()).isEqualTo(RunWorkspaceLease.State.RELEASED);
        assertThat(released.getReleasedAt()).isNotNull();
        // Retention is separate from unlocking: a Direct directory stays user-owned.
        assertThat(released.isRetained()).isTrue();
    }

    @Test
    void releaseRejectsUnrecordedLease() {
        UUID leaseId = UUID.randomUUID();
        WorkspaceLease ghost = new WorkspaceLease(leaseId, UUID.randomUUID(), WorkspaceKind.SCRATCH,
                temp.resolve("ghost"), null, null, null);
        assertThatThrownBy(() -> service().release(ghost, new StopProof(ghost.runId(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace lease " + leaseId + " is not recorded");
    }

    @Test
    void releaseUsesThePersistedRowAsAuthorityNotTheSuppliedRecord() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("release-authority-repo"));
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        WorkspaceLease leaseA = service().acquire(hostSpec(runA, WorkspaceMode.DIRECT, ws.toString(), null));
        WorkspaceLease leaseB = service().acquire(hostSpec(runB, null, null, null));

        // A forged record carrying row B's lease id and run A's run id used to unlock row B with run A's proof.
        WorkspaceLease forged = new WorkspaceLease(leaseB.leaseId(), runA, leaseB.kind(),
                leaseB.localRoot(), leaseB.sourceRoot(), leaseB.runtimeRoot(), leaseB.baseCommit());
        assertThatThrownBy(() -> service().release(forged, new StopProof(runA, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stop proof run " + runA + " does not match workspace lease run " + runB);

        assertThat(leases.findById(leaseB.leaseId()).orElseThrow().getState())
                .isEqualTo(RunWorkspaceLease.State.ACTIVE);
        assertThat(leases.findById(leaseA.leaseId()).orElseThrow().getState())
                .isEqualTo(RunWorkspaceLease.State.ACTIVE);
    }

    // ----------------------------------------------------------------- capture

    @Test
    void captureRequiresVerifiedStopProofAndMatchingRuntime() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("capture-proof-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));
        UUID otherRun = UUID.randomUUID();
        ExecutionBackend backend = new CopyingBackend(ws);
        Path resultDir = resultRoot.resolve(runId.toString());

        assertThatThrownBy(() -> service().capture(lease, backend, handle(runId), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace capture requires a verified stop proof for run " + runId);
        assertThatThrownBy(() -> service().capture(lease, backend, handle(runId), new StopProof(runId, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stop proof does not verify that all writers stopped for run " + runId);
        assertThatThrownBy(() -> service().capture(lease, backend, handle(runId), new StopProof(otherRun, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stop proof run " + otherRun + " does not match workspace lease run " + runId);
        assertThatThrownBy(() -> service().capture(lease, backend, handle(otherRun), new StopProof(runId, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime handle run " + otherRun + " does not match workspace lease run " + runId);
        assertThat(Files.notExists(resultDir)).isTrue();

        ArtifactBundle bundle = service().capture(lease, backend, handle(runId), new StopProof(runId, true));
        assertThat(bundle.complete()).isTrue();
        assertThat(bundle.directory()).isEqualTo(resultDir);
    }

    @Test
    void captureDirectDiffAgainstBaselineWritesExactBytes() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("capture-direct-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha");
        Files.writeString(ws.resolve("b.txt"), "bravo");
        Files.writeString(ws.resolve("gone.txt"), "gone");

        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        Files.writeString(ws.resolve("b.txt"), "BRAVO-changed");
        Files.writeString(ws.resolve("c.txt"), "charlie");
        Files.delete(ws.resolve("gone.txt"));

        ArtifactBundle bundle = service().capture(lease, new CopyingBackend(ws), handle(runId),
                new StopProof(runId, true));

        Path resultDir = resultRoot.resolve(runId.toString());
        assertThat(bundle.directory()).isEqualTo(resultDir);
        assertThat(bundle.complete()).isTrue();
        assertThat(Files.readString(resultDir.resolve("a.txt"))).isEqualTo("alpha");
        assertThat(Files.readString(resultDir.resolve("b.txt"))).isEqualTo("BRAVO-changed");
        assertThat(Files.readString(resultDir.resolve("c.txt"))).isEqualTo("charlie");
        assertThat(Files.notExists(resultDir.resolve("gone.txt"))).isTrue();

        String manifest = sha256Hex("alpha") + "  a.txt\n"
                + sha256Hex("BRAVO-changed") + "  b.txt\n"
                + sha256Hex("charlie") + "  c.txt\n";
        assertThat(Files.readString(resultDir.resolve("manifest.txt"))).isEqualTo(manifest);
        assertThat(bundle.manifestSha256())
                .isEqualTo(sha256Hex(Files.readAllBytes(resultDir.resolve("manifest.txt"))));
        assertThat(Files.readString(resultDir.resolve("changes.txt")))
                .isEqualTo("~ b.txt\n+ c.txt\n- gone.txt\n");
    }

    @Test
    void captureDoesNotAttributePreRunEditsToTheRun() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("pre-run-edit-repo"));
        Files.writeString(ws.resolve("user.txt"), "original");
        // The user edits before the run starts: this is not a run-produced change.
        Files.writeString(ws.resolve("user.txt"), "user-edit");

        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        ArtifactBundle bundle = service().capture(lease, new CopyingBackend(ws), handle(runId),
                new StopProof(runId, true));

        Path resultDir = resultRoot.resolve(runId.toString());
        assertThat(bundle.complete()).isTrue();
        assertThat(Files.readString(resultDir.resolve("user.txt"))).isEqualTo("user-edit");
        assertThat(Files.readString(resultDir.resolve("changes.txt"))).isEmpty();
    }

    @Test
    void captureRejectsEscapingLinkInExport() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("escaping-link-repo"));
        Files.writeString(ws.resolve("ok.txt"), "ok\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        Path outside = Files.createDirectories(temp.resolve("outside-secret"));
        Files.writeString(outside.resolve("secret.txt"), "outside\n");
        ExecutionBackend backend = new CopyingBackend(ws, destination -> {
            try {
                createDirectoryAlias(destination.resolve("escape"), outside);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Path escape = resultRoot.resolve(runId.toString()).resolve("escape");
        try {
            assertThatThrownBy(() -> service().capture(lease, backend, handle(runId), new StopProof(runId, true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unsafe link escapes artifact directory: escape");
        } finally {
            Files.deleteIfExists(escape);
        }
    }

    @Test
    void captureExcludesSecretBearingFiles() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("secret-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        Files.writeString(ws.resolve(".env"), "TOKEN=secret\n");
        Files.writeString(ws.resolve("credentials.json"), "{\"k\":\"v\"}\n");
        Files.writeString(ws.resolve("server.pem"), "-----BEGIN\n");

        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));
        ArtifactBundle bundle = service().capture(lease, new CopyingBackend(ws), handle(runId),
                new StopProof(runId, true));

        Path resultDir = resultRoot.resolve(runId.toString());
        assertThat(bundle.complete()).isTrue();
        assertThat(Files.readString(resultDir.resolve("a.txt"))).isEqualTo("alpha\n");
        assertThat(Files.notExists(resultDir.resolve(".env"))).isTrue();
        assertThat(Files.notExists(resultDir.resolve("credentials.json"))).isTrue();
        assertThat(Files.notExists(resultDir.resolve("server.pem"))).isTrue();
        assertThat(Files.readString(resultDir.resolve("manifest.txt")))
                .isEqualTo(sha256Hex("alpha\n") + "  a.txt\n");
        assertThat(Files.readString(resultDir.resolve("changes.txt"))).isEmpty();
    }

    @Test
    void captureRefusesEscapingLinkBeforeRemovingAnySecretBearingFile() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("escape-before-secret-repo"));
        Files.writeString(ws.resolve("ok.txt"), "ok\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        Path outside = Files.createDirectories(temp.resolve("outside-secret-target"));
        Files.writeString(outside.resolve(".env"), "TOKEN=outside-secret\n");
        Files.writeString(outside.resolve("id_rsa"), "private-key-material\n");
        Files.writeString(outside.resolve("server.key"), "key-material\n");
        ExecutionBackend backend = new CopyingBackend(ws, destination -> {
            try {
                createDirectoryAlias(destination.resolve("escape"), outside);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Path escape = resultRoot.resolve(runId.toString()).resolve("escape");
        try {
            assertThatThrownBy(() -> service().capture(lease, backend, handle(runId), new StopProof(runId, true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unsafe link escapes artifact directory: escape");
        } finally {
            Files.deleteIfExists(escape);
        }
        // The refusal must happen before any deletion: secret-bearing files outside the artifact root survive.
        assertThat(Files.readString(outside.resolve(".env"))).isEqualTo("TOKEN=outside-secret\n");
        assertThat(Files.readString(outside.resolve("id_rsa"))).isEqualTo("private-key-material\n");
        assertThat(Files.readString(outside.resolve("server.key"))).isEqualTo("key-material\n");
    }

    @Test
    void captureSkipsAnUnresolvableSecretNamedLinkInsteadOfAborting() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("dangling-secret-link-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        // The exported tree is legal apart from one broken, secret-named link: unresolved, it
        // has no secret content to remove and no target that could be deleted, so it must be
        // skipped as its own case instead of failing the whole capture.
        Path neverCreated = temp.resolve("never-created-env-target");
        ExecutionBackend backend = new CopyingBackend(ws, destination -> {
            try {
                createDanglingFileLink(destination.resolve(".env"), neverCreated);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        ArtifactBundle bundle = service().capture(lease, backend, handle(runId), new StopProof(runId, true));

        Path resultDir = resultRoot.resolve(runId.toString());
        assertThat(bundle.complete()).isTrue();
        assertThat(Files.readString(resultDir.resolve("a.txt"))).isEqualTo("alpha\n");
        // Skipped and recorded in the result: the broken link stays exactly as exported,
        // never resolved, never followed, never deleted and never reaching a target.
        assertThat(Files.exists(resultDir.resolve(".env"), LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(resultDir.resolve(".env"))).isTrue();
        assertThat(Files.exists(neverCreated)).isFalse();
        // Secret-named entries stay out of the content-addressed manifest, resolvable or not.
        assertThat(Files.readString(resultDir.resolve("manifest.txt")))
                .isEqualTo(sha256Hex("alpha\n") + "  a.txt\n");
    }

    @Test
    void captureSkipsAnUnresolvableSecretNamedJunctionInsteadOfAborting() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("dangling-secret-junction-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        // The same situation as the broken symbolic link above, with the other Windows link
        // shape: a directory junction whose target does not exist. The JDK walker cannot
        // descend into it and reports it through visitFileFailed before any visitor callback
        // runs, which used to abort the whole capture.
        Path neverCreated = temp.resolve("never-created-env-junction-target");
        ExecutionBackend backend = new CopyingBackend(ws, destination -> {
            try {
                createDanglingDirectoryAlias(destination.resolve(".env"), neverCreated);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Path resultDir = resultRoot.resolve(runId.toString());
        try {
            ArtifactBundle bundle = service().capture(lease, backend, handle(runId), new StopProof(runId, true));

            assertThat(bundle.complete()).isTrue();
            assertThat(Files.readString(resultDir.resolve("a.txt"))).isEqualTo("alpha\n");
            // Skipped and recorded in the result: the broken junction stays exactly as exported,
            // never resolved, never followed, never deleted and never reaching a target.
            assertThat(Files.exists(resultDir.resolve(".env"), LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.exists(neverCreated)).isFalse();
            // Secret-named entries stay out of the content-addressed manifest, resolvable or not.
            assertThat(Files.readString(resultDir.resolve("manifest.txt")))
                    .isEqualTo(sha256Hex("alpha\n") + "  a.txt\n");
        } finally {
            Files.deleteIfExists(resultDir.resolve(".env"));
        }
    }

    @Test
    void captureSkipsALinkLikeDirectoryInsteadOfDescendingIt() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("alias-directory-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        Files.createDirectories(ws.resolve("nested"));
        Files.writeString(ws.resolve("nested").resolve("inner.txt"), "inner\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        // The exported tree contains a directory alias to a directory inside the artifact
        // (a Windows directory junction here, a symbolic link elsewhere). Walking through
        // the alias enumerates the same content a second time under the alias path -- and an
        // alias whose target leads back to the walk root would expand the walk until the OS
        // path limit, which is exactly what a self-referential junction does in real user
        // repositories. The alias is therefore skipped as an entry and never descended.
        Path resultDir = resultRoot.resolve(runId.toString());
        ExecutionBackend backend = new CopyingBackend(ws, destination -> {
            try {
                createDirectoryAlias(destination.resolve("alias"), destination.resolve("nested"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        try {
            ArtifactBundle bundle = service().capture(lease, backend, handle(runId), new StopProof(runId, true));

            assertThat(bundle.complete()).isTrue();
            assertThat(Files.readString(resultDir.resolve("manifest.txt")))
                    .isEqualTo(sha256Hex("alpha\n") + "  a.txt\n"
                            + sha256Hex("inner\n") + "  nested/inner.txt\n");
            // The alias itself stays exactly as exported; the content is recorded once, under its real path.
            assertThat(Files.exists(resultDir.resolve("alias"), LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.readString(resultDir.resolve("nested").resolve("inner.txt"))).isEqualTo("inner\n");
        } finally {
            Files.deleteIfExists(resultDir.resolve("alias"));
        }
    }

    @Test
    void captureSkipsAnUnresolvableOrdinaryNamedJunctionInsteadOfAborting() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("dangling-ordinary-junction-repo"));
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        UUID runId = UUID.randomUUID();
        WorkspaceLease lease = service().acquire(hostSpec(runId, WorkspaceMode.DIRECT, ws.toString(), null));

        // A dangling directory junction with an ordinary name: it exists as itself but
        // resolves nowhere. Capture must skip it as its own case and record it in the
        // result, exactly like the secret-named case -- only an escaping resolvable link
        // keeps being refused.
        Path neverCreated = temp.resolve("never-created-ordinary-junction-target");
        ExecutionBackend backend = new CopyingBackend(ws, destination -> {
            try {
                createDanglingDirectoryAlias(destination.resolve("legacy-junction"), neverCreated);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Path resultDir = resultRoot.resolve(runId.toString());
        try {
            ArtifactBundle bundle = service().capture(lease, backend, handle(runId), new StopProof(runId, true));

            assertThat(bundle.complete()).isTrue();
            assertThat(Files.readString(resultDir.resolve("a.txt"))).isEqualTo("alpha\n");
            assertThat(Files.exists(resultDir.resolve("legacy-junction"), LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.exists(neverCreated)).isFalse();
            assertThat(Files.readString(resultDir.resolve("manifest.txt")))
                    .isEqualTo(sha256Hex("alpha\n") + "  a.txt\n");
        } finally {
            Files.deleteIfExists(resultDir.resolve("legacy-junction"));
        }
    }

    // ------------------------------------------------------------ bounded walks

    @Test
    void boundedWalkRefusesWhenTheDepthBudgetIsExceeded() throws Exception {
        Path root = Files.createDirectories(temp.resolve("depth-budget-root"));
        Path deep = Files.createDirectories(root.resolve("one").resolve("two").resolve("three"));

        // A walk is bounded by an explicit depth budget (injected small here), so a
        // pathological tree is refused with a clear message instead of walking forever.
        Throwable failure = catchThrowable(
                () -> RunWorkspaceService.walkBounded(root, 2, 1_000L, new SimpleFileVisitor<Path>() { }));

        assertThat(failure).as("a walk deeper than the depth budget must refuse, not continue")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Workspace walk exceeded the depth budget of 2 levels at " + deep);
    }

    @Test
    void boundedWalkRefusesWhenTheEntryBudgetIsExceeded() throws Exception {
        Path root = Files.createDirectories(temp.resolve("entry-budget-root"));
        Files.writeString(root.resolve("one.txt"), "one\n");
        Files.writeString(root.resolve("two.txt"), "two\n");
        Files.writeString(root.resolve("three.txt"), "three\n");

        Throwable failure = catchThrowable(
                () -> RunWorkspaceService.walkBounded(root, 16, 2L, new SimpleFileVisitor<Path>() { }));

        assertThat(failure).as("a walk wider than the entry budget must refuse, not continue")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Workspace walk exceeded the entry budget of 2 entries at");
    }

    @Test
    void boundedWalkSkipsALinkLikeDirectoryInsteadOfDescendingIt() throws Exception {
        Path root = Files.createDirectories(temp.resolve("bounded-walk-root"));
        Path nested = Files.createDirectories(root.resolve("nested"));
        Files.writeString(nested.resolve("inner.txt"), "inner\n");
        Path alias = root.resolve("alias");
        createDirectoryAlias(alias, nested);

        List<String> visited = new ArrayList<>();
        try {
            RunWorkspaceService.walkBounded(root, 16, 1_000L, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    visited.add(root.relativize(dir).toString());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    visited.add(root.relativize(file).toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } finally {
            Files.deleteIfExists(alias);
        }

        // The alias is delivered as an entry but never walked through: no path below it is
        // ever visited, so an alias leading back to the walk root cannot expand the walk.
        // (On Windows the junction arrives through preVisitDirectory; a POSIX symlink
        // arrives through visitFile -- both are never descended.)
        assertThat(visited).containsExactlyInAnyOrder(
                "",
                alias.getFileName().toString(),
                nested.getFileName().toString(),
                root.relativize(nested.resolve("inner.txt")).toString());
    }

    // ------------------------------------------------- cleanup / orphan sweeper

    @Test
    void sweepProtectsOwnedDirectoriesAndRemovesOnlyStoppedUnretainedScratch() throws Exception {
        Path root = Files.createDirectories(temp.resolve("sweep-root"));
        UUID releasedScratchRun = UUID.randomUUID();
        UUID activeScratchRun = UUID.randomUUID();
        UUID retainedWorktreeRun = UUID.randomUUID();
        UUID directRun = UUID.randomUUID();
        UUID unknownRun = UUID.randomUUID();

        Path releasedScratch = Files.createDirectories(root.resolve(releasedScratchRun.toString()));
        Path activeScratch = Files.createDirectories(root.resolve(activeScratchRun.toString()));
        Path retainedWorktree = Files.createDirectories(root.resolve(retainedWorktreeRun.toString()));
        Path direct = Files.createDirectories(root.resolve(directRun.toString()));
        Path unknown = Files.createDirectories(root.resolve(unknownRun.toString()));
        Files.writeString(releasedScratch.resolve("f.txt"), "sweep-me\n");
        Files.writeString(activeScratch.resolve("f.txt"), "still-running\n");
        Files.writeString(retainedWorktree.resolve("tracked.txt"), "v1\n");
        Files.writeString(direct.resolve("user.txt"), "user-data\n");
        Files.writeString(unknown.resolve("f.txt"), "unknown\n");

        leases.save(leaseRow(releasedScratchRun, WorkspaceKind.SCRATCH, RunWorkspaceLease.State.RELEASED, releasedScratch, false));
        leases.save(leaseRow(activeScratchRun, WorkspaceKind.SCRATCH, RunWorkspaceLease.State.ACTIVE, activeScratch, false));
        leases.save(leaseRow(retainedWorktreeRun, WorkspaceKind.WORKTREE, RunWorkspaceLease.State.RELEASED, retainedWorktree, true));
        leases.save(leaseRow(directRun, WorkspaceKind.DIRECT, RunWorkspaceLease.State.RELEASED, direct, true));

        FileTime old = FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS));
        for (Path dir : List.of(releasedScratch, activeScratch, retainedWorktree, direct, unknown)) {
            Files.setLastModifiedTime(dir, old);
        }

        WorkspaceManager manager = new WorkspaceManager(root.toString(), leases);
        manager.sweepOrphanWorkspaces();

        assertThat(Files.exists(releasedScratch)).isFalse();
        assertThat(Files.exists(activeScratch)).as("an unresolved lease must not be swept").isTrue();
        assertThat(Files.exists(retainedWorktree)).as("a retained worktree must survive cleanup").isTrue();
        assertThat(Files.exists(direct)).as("a Direct directory must survive cleanup").isTrue();
        assertThat(Files.exists(unknown)).as("a directory with no positive ownership must survive").isTrue();
        assertThat(Files.readString(retainedWorktree.resolve("tracked.txt"))).isEqualTo("v1\n");

        // Explicit terminal cleanup must not remove a user-owned Direct directory either.
        manager.cleanup(directRun);
        assertThat(Files.exists(direct)).isTrue();
        assertThat(Files.readString(direct.resolve("user.txt"))).isEqualTo("user-data\n");
    }

    @Test
    void legacySweeperWithoutLedgerKeepsExistingAgeBasedBehaviour() throws Exception {
        Path root = Files.createDirectories(temp.resolve("legacy-sweep-root"));
        Path old = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Path fresh = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Files.writeString(old.resolve("f.txt"), "old\n");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        new WorkspaceManager(root.toString()).sweepOrphanWorkspaces();

        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void cleanupNeedsTheRecordedStopEvidenceForALeasedScratchWorkspace() throws Exception {
        Path root = Files.createDirectories(temp.resolve("cleanup-evidence-root"));
        UUID runId = UUID.randomUUID();
        Path workspace = Files.createDirectories(root.resolve(runId.toString()));
        Files.writeString(workspace.resolve("f.txt"), "platform-scratch\n");
        // The service keeps scratch local_root under its own runtime root, never under the
        // workspace root: the lease row is the evidence, not the directory's location.
        Path scratchRoot = Files.createDirectories(runtimeRoot.resolve("scratch").resolve(runId.toString()));
        RunWorkspaceLease row = leases.save(rowFor(runId, WorkspaceKind.SCRATCH,
                RunWorkspaceLease.State.ACTIVE, scratchRoot, null, false));

        WorkspaceManager manager = new WorkspaceManager(root.toString(), leases);
        manager.cleanup(runId);
        assertThat(Files.exists(workspace)).as("an unresolved lease must survive cleanup").isTrue();
        assertThat(Files.readString(workspace.resolve("f.txt"))).isEqualTo("platform-scratch\n");

        row.setState(RunWorkspaceLease.State.RELEASED);
        row.setReleasedAt(Instant.now());
        leases.save(row);
        manager.cleanup(runId);
        assertThat(Files.exists(workspace)).as("a released, unretained scratch lease is removable").isFalse();
    }

    @Test
    void cleanupKeepsAReleasedButRetainedScratchWorkspace() throws Exception {
        Path root = Files.createDirectories(temp.resolve("cleanup-retained-root"));
        UUID runId = UUID.randomUUID();
        Path workspace = Files.createDirectories(root.resolve(runId.toString()));
        Files.writeString(workspace.resolve("f.txt"), "retained\n");
        Path scratchRoot = Files.createDirectories(runtimeRoot.resolve("scratch").resolve(runId.toString()));
        leases.save(rowFor(runId, WorkspaceKind.SCRATCH, RunWorkspaceLease.State.RELEASED,
                scratchRoot, null, true));

        new WorkspaceManager(root.toString(), leases).cleanup(runId);

        assertThat(Files.exists(workspace)).as("a retained workspace must survive cleanup").isTrue();
        assertThat(Files.readString(workspace.resolve("f.txt"))).isEqualTo("retained\n");
    }

    @Test
    void sweeperSweepsStoppedRunOrphansAndProtectsUserOwnedTrees() throws Exception {
        Path root = Files.createDirectories(temp.resolve("sweeper-ownership-root"));
        Path userRepo = Files.createDirectories(temp.resolve("sweeper-user-repo"));
        Files.writeString(userRepo.resolve("user.txt"), "user-data\n");

        UUID stoppedRun = UUID.randomUUID();
        UUID retainedRun = UUID.randomUUID();
        UUID directRun = UUID.randomUUID();
        // Production shapes: local_root lives under the platform runtime root, so ownership of
        // the swept directory is established by the run id it is named after, not by path equality.
        Path stoppedScratchRoot = Files.createDirectories(runtimeRoot.resolve("scratch").resolve(stoppedRun.toString()));
        Path managedWorktree = Files.createDirectories(runtimeRoot.resolve("worktrees").resolve(retainedRun.toString()));

        Path stoppedOrphan = Files.createDirectories(root.resolve(stoppedRun.toString()));
        Path retainedOrphan = Files.createDirectories(root.resolve(retainedRun.toString()));
        Path directOrphan = Files.createDirectories(root.resolve(directRun.toString()));
        Files.writeString(stoppedOrphan.resolve("f.txt"), "sweep-me\n");
        Files.writeString(retainedOrphan.resolve("tracked.txt"), "v1\n");
        Files.writeString(directOrphan.resolve("f.txt"), "leftover\n");

        leases.save(rowFor(stoppedRun, WorkspaceKind.SCRATCH, RunWorkspaceLease.State.RELEASED,
                stoppedScratchRoot, null, false));
        leases.save(rowFor(retainedRun, WorkspaceKind.WORKTREE, RunWorkspaceLease.State.RELEASED,
                managedWorktree, userRepo, true));
        leases.save(rowFor(directRun, WorkspaceKind.DIRECT, RunWorkspaceLease.State.RELEASED,
                userRepo, userRepo, true));

        FileTime old = FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS));
        for (Path dir : List.of(stoppedOrphan, retainedOrphan, directOrphan)) {
            Files.setLastModifiedTime(dir, old);
        }

        new WorkspaceManager(root.toString(), leases).sweepOrphanWorkspaces();

        assertThat(Files.exists(stoppedOrphan))
                .as("a stopped, unretained, platform-owned orphan is swept").isFalse();
        assertThat(Files.exists(retainedOrphan)).as("a retained worktree's directory survives").isTrue();
        assertThat(Files.readString(retainedOrphan.resolve("tracked.txt"))).isEqualTo("v1\n");
        assertThat(Files.isDirectory(managedWorktree)).as("the managed worktree survives").isTrue();
        assertThat(Files.exists(directOrphan)).as("a Direct run directory survives").isTrue();
        assertThat(Files.readString(directOrphan.resolve("f.txt"))).isEqualTo("leftover\n");
        assertThat(Files.readString(userRepo.resolve("user.txt"))).as("a user repository is never swept")
                .isEqualTo("user-data\n");
    }

    // ------------------------------------------------------------- git timeouts

    @Test
    void gitStallIsBoundedByTheTimeoutInsteadOfAnUnboundedRead() throws Exception {
        Path repo = temp.resolve("stalled-probe-repo");
        initDisposableRepo(repo);
        Path shim = writeGitShim(windowsShimStallBody(4), "#!/bin/sh\nsleep 4\nexit 0\n");
        List<String> shimCommand = gitShimCommand(shim);
        UUID runId = UUID.randomUUID();

        long startedAt = System.nanoTime();
        Throwable failure = catchThrowable(() -> serviceWithGit(shimCommand, Duration.ofSeconds(1))
                .acquire(hostSpec(runId, WorkspaceMode.WORKTREE, repo.toString(), null)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).as("a stalled git must be cut off by the timeout, not awaited")
                .isLessThan(Duration.ofSeconds(5));
        List<String> expectedArgv = new ArrayList<>(shimCommand);
        expectedArgv.addAll(List.of("-C", repo.toString(), "rev-parse", "--git-dir"));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Worktree workspace requires a git repository: " + repo);
        assertThat(failure).hasRootCauseMessage("git timed out after 1s: " + expectedArgv);
        assertThat(leases.findByRunId(runId)).isEmpty();
    }

    @Test
    void gitStallWhileHoldingTheLocksDoesNotWedgeLaterAcquisitions() throws Exception {
        Path repo = temp.resolve("stalled-worktree-repo");
        initDisposableRepo(repo);
        Path shim = writeGitShim(windowsShimRevParseBody(8), posixShimRevParseBody(8));
        List<String> shimCommand = gitShimCommand(shim);
        UUID runId = UUID.randomUUID();
        String expectedCommit = "a".repeat(40);

        // The stall is inside worktree creation, which runs under the lease lock and the
        // common-git lock; the shim answers both rev-parse probes so only that call stalls.
        long startedAt = System.nanoTime();
        Throwable failure = catchThrowable(() -> serviceWithGit(shimCommand, Duration.ofSeconds(1))
                .acquire(hostSpec(runId, WorkspaceMode.WORKTREE, repo.toString(), null)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).as("the timeout bounds the git call that holds both locks")
                .isLessThan(Duration.ofSeconds(5));
        List<String> expectedArgv = new ArrayList<>(shimCommand);
        expectedArgv.addAll(List.of("-C", repo.toRealPath().toString(), "worktree", "add", "--detach",
                runtimeRoot.toRealPath().resolve("worktrees").resolve(runId.toString()).toString(), expectedCommit));
        assertThat(failure).isInstanceOf(UncheckedIOException.class);
        assertThat(failure).hasRootCauseMessage("git timed out after 1s: " + expectedArgv);
        assertThat(leases.findByRunId(runId)).isEmpty();

        // Neither lock survived the timeout, and later acquisitions still proceed.
        UUID laterRun = UUID.randomUUID();
        WorkspaceLease later = service().acquire(hostSpec(laterRun, WorkspaceMode.WORKTREE, repo.toString(), null));
        assertThat(Files.readString(later.localRoot().resolve("tracked.txt"))).isEqualTo("v1\n");
    }

    // ------------------------------------------------------------------ helpers

    /** A lease row with explicit local/source roots, mirroring how the service persists them. */
    private RunWorkspaceLease rowFor(UUID runId, WorkspaceKind kind, RunWorkspaceLease.State state,
            Path localRoot, Path sourceRoot, boolean retained) throws IOException {
        return RunWorkspaceLease.builder()
                .leaseId(UUID.randomUUID())
                .runId(runId)
                .kind(kind)
                .state(state)
                .localRoot(localRoot.toRealPath().toString())
                .sourceRoot(sourceRoot == null ? null : sourceRoot.toRealPath().toString())
                .runtimeRoot(runtimeRoot.resolve("runs").resolve(runId.toString()).toString())
                .retained(retained)
                .acquiredAt(Instant.now())
                .build();
    }

    /** Writes a controlled git stand-in under the temp directory; the real git installation is never touched. */
    private Path writeGitShim(String windowsBody, String posixBody) throws IOException {
        boolean windows = isWindows();
        Path shim = temp.resolve(windows ? "git-shim.cmd" : "git-shim.sh");
        Files.writeString(shim, windows ? windowsBody : posixBody);
        return shim;
    }

    private static List<String> gitShimCommand(Path shim) {
        return isWindows() ? List.of("cmd", "/c", shim.toString()) : List.of("/bin/sh", shim.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /** A shim that never answers and holds its output open for the given seconds. */
    private static String windowsShimStallBody(int seconds) {
        return "@echo off\r\nping -n " + (seconds + 1) + " 127.0.0.1 >nul 2>&1\r\nexit /b 0\r\n";
    }

    /** Answers both rev-parse probes, then stalls (holding stdout open) on worktree creation. */
    private static String windowsShimRevParseBody(int stallSeconds) {
        return "@echo off\r\n"
                + "if \"%3\"==\"worktree\" (\r\n"
                + "  ping -n " + (stallSeconds + 1) + " 127.0.0.1 >nul 2>&1\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "if \"%4\"==\"--git-dir\" (\r\n"
                + "  echo .git\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "echo " + "a".repeat(40) + "\r\n"
                + "exit /b 0\r\n";
    }

    private static String posixShimRevParseBody(int stallSeconds) {
        return "#!/bin/sh\n"
                + "if [ \"$3\" = \"worktree\" ]; then\n"
                + "  sleep " + stallSeconds + "\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [ \"$4\" = \"--git-dir\" ]; then\n"
                + "  echo .git\n"
                + "  exit 0\n"
                + "fi\n"
                + "echo " + "a".repeat(40) + "\n"
                + "exit 0\n";
    }

    /** Answers both rev-parse probes, then fails worktree creation after creating a partial worktree through the given path. */
    private static String windowsShimFailingWorktreeAddBody() {
        return "@echo off\r\n"
                + "if \"%3\"==\"worktree\" (\r\n"
                + "  mkdir \"%~6\"\r\n"
                + "  echo partial>\"%~6\\partial.txt\"\r\n"
                + "  exit /b 1\r\n"
                + ")\r\n"
                + "if \"%4\"==\"--git-dir\" (\r\n"
                + "  echo .git\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "echo " + "a".repeat(40) + "\r\n"
                + "exit /b 0\r\n";
    }

    private static String posixShimFailingWorktreeAddBody() {
        return "#!/bin/sh\n"
                + "if [ \"$3\" = \"worktree\" ]; then\n"
                + "  mkdir -p \"$6\"\n"
                + "  echo partial > \"$6/partial.txt\"\n"
                + "  exit 1\n"
                + "fi\n"
                + "if [ \"$4\" = \"--git-dir\" ]; then\n"
                + "  echo .git\n"
                + "  exit 0\n"
                + "fi\n"
                + "echo " + "a".repeat(40) + "\n"
                + "exit 0\n";
    }

    /** Speaks exactly what git speaks in a SHA-256 repository: rev-parse --verify answers with the 64-hex commit id. */
    private static String windowsShimSha256RepoBody(Path argvLog, String commit) {
        return "@echo off\r\n"
                + "echo %* >> \"" + argvLog + "\"\r\n"
                + "if \"%3\"==\"worktree\" (\r\n"
                + "  mkdir \"%~6\"\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "if \"%4\"==\"--git-dir\" (\r\n"
                + "  echo .git\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "echo " + commit + "\r\n"
                + "exit /b 0\r\n";
    }

    private static String posixShimSha256RepoBody(Path argvLog, String commit) {
        return "#!/bin/sh\n"
                + "echo \"$*\" >> \"" + argvLog + "\"\n"
                + "if [ \"$3\" = \"worktree\" ]; then\n"
                + "  mkdir -p \"$6\"\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [ \"$4\" = \"--git-dir\" ]; then\n"
                + "  echo .git\n"
                + "  exit 0\n"
                + "fi\n"
                + "echo " + commit + "\n"
                + "exit 0\n";
    }

    private RunWorkspaceLease leaseRow(UUID runId, WorkspaceKind kind, RunWorkspaceLease.State state,
            Path localRoot, boolean retained) throws IOException {
        return RunWorkspaceLease.builder()
                .leaseId(UUID.randomUUID())
                .runId(runId)
                .kind(kind)
                .state(state)
                .localRoot(localRoot.toRealPath().toString())
                .sourceRoot(localRoot.toRealPath().toString())
                .runtimeRoot(runtimeRoot.resolve("runs").resolve(runId.toString()).toString())
                .retained(retained)
                .acquiredAt(Instant.now())
                .build();
    }

    private RunWorkspaceService service() {
        return new RunWorkspaceService(leases, runtimeRoot, resultRoot);
    }

    private RunWorkspaceService serviceWithGit(List<String> gitCommand, Duration timeout) {
        return new RunWorkspaceService(leases, runtimeRoot, resultRoot, gitCommand, timeout);
    }

    private static ExecutionSpec hostSpec(UUID runId, WorkspaceMode workspaceMode, String workspacePath, String baseRef) {
        return new ExecutionSpec(runId, UUID.randomUUID(), "opencode", ExecutionMode.HOST,
                new AgentExecutionSettings("opencode", ExecutionMode.HOST, workspaceMode, workspacePath, baseRef),
                null, "rev-1", Instant.now().plusSeconds(3600));
    }

    private static RuntimeHandle handle(UUID runId) {
        return new RuntimeHandle(runId, ExecutionMode.HOST, "env-" + runId, "owned:" + runId,
                java.net.URI.create("http://127.0.0.1:9400/" + runId));
    }

    private static void initDisposableRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "task6@aria.test");
        git(repo, "config", "user.name", "task6-test");
        git(repo, "config", "commit.gpgsign", "false");
        // Deterministic checkout bytes: never rewrite line endings on checkout.
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("tracked.txt"), "v1\n");
        git(repo, "add", "tracked.txt");
        git(repo, "commit", "-q", "-m", "init");
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("git timed out: %s", argv).isTrue();
        assertThat(p.exitValue()).as("git %s failed: %s", argv, out).isZero();
        return out.trim();
    }

    private static void createDirectoryAlias(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(20, TimeUnit.SECONDS)).as("mklink /J timed out").isTrue();
            assertThat(p.exitValue()).as("mklink /J failed: %s", out).isZero();
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /** A file link whose target does not exist and never will: `mklink` on Windows, a symbolic link elsewhere. */
    private static void createDanglingFileLink(Path link, Path target) throws Exception {
        if (isWindows()) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(20, TimeUnit.SECONDS)).as("mklink timed out").isTrue();
            assertThat(p.exitValue()).as("mklink failed: %s", out).isZero();
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /** A directory link whose target does not exist: a junction (`mklink /J`) on Windows, a symbolic link elsewhere. */
    private static void createDanglingDirectoryAlias(Path link, Path target) throws Exception {
        if (isWindows()) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(20, TimeUnit.SECONDS)).as("mklink /J timed out").isTrue();
            assertThat(p.exitValue()).as("mklink /J failed: %s", out).isZero();
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test double: exports by copying a directory tree, optionally mutating the destination. */
    private static final class CopyingBackend implements ExecutionBackend {

        private final Path source;
        private final Consumer<Path> afterCopy;

        CopyingBackend(Path source) {
            this(source, null);
        }

        CopyingBackend(Path source, Consumer<Path> afterCopy) {
            this.source = source;
            this.afterCopy = afterCopy;
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            try {
                Files.createDirectories(destination);
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, destination.resolve(source.relativize(file)),
                                StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            if (afterCopy != null) {
                afterCopy.accept(destination);
            }
        }

        @Override
        public void destroy(RuntimeHandle handle) {
        }
    }
}
