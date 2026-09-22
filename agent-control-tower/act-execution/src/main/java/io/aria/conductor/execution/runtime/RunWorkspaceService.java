package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunWorkspaceLease;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.repository.RunWorkspaceLeaseRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Lease-based workspace ownership (spec 5.2). Every acquisition records the
 * canonical local root, the user-owned source root, the platform-owned run
 * configuration root and the lease kind; only one platform writer is admitted
 * for overlapping directory trees (ancestor/descendant and link aliases
 * included). Worktrees are created from a resolved commit with explicit git
 * argv and never touch the source index or uncommitted edits. {@code release}
 * requires a matching verified {@link StopProof}; retention stays a separate
 * concern from unlocking.
 *
 * <p>Validation failure is always an explicit rejection -- a worktree never
 * falls back to Direct or Scratch, and a Direct selection must be an existing
 * explicitly selected directory.
 *
 * <p>This service is constructed by the runtime cutover wiring; it takes no
 * Spring annotation so tests and callers use one explicit constructor.
 */
public class RunWorkspaceService implements WorkspaceService {

    /** Process-wide serialization of lease admission and release. */
    private static final ReentrantLock LEASE_LOCK = new ReentrantLock();

    private static final String BASELINE_FILE = "baseline.manifest";
    private static final String MANIFEST_FILE = "manifest.txt";
    private static final String CHANGES_FILE = "changes.txt";

    /** Generated secret-bearing files never enter retained artifacts. */
    private static final List<String> SECRET_FILE_NAMES = List.of(
            ".env", ".env.local", "credentials.json", "runtime-credentials.json", "id_rsa");
    private static final List<String> SECRET_FILE_SUFFIXES = List.of(".pem", ".key");

    /**
     * A resolved git commit as emitted by {@code rev-parse --verify}: full lowercase
     * hexadecimal, 40 digits in a SHA-1 repository and 64 in a SHA-256 repository (the
     * lease entity's {@code base_commit} column is 64 wide for exactly that reason).
     */
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}(?:[0-9a-f]{24})?");

    private static final long GIT_TIMEOUT_SECONDS = 60;

    /**
     * Budgets of the bounded walks ({@link #walkBounded}): no walk this service performs
     * descends deeper than this many levels below its root or visits more entries than
     * this below its root. Real workspace trees -- user repositories and exported
     * artifacts -- stay far below both; a self-referential junction or any other
     * pathological tree is refused with a clear message instead of expanding until the
     * OS path limit.
     */
    static final int MAX_WALK_DEPTH = 128;
    static final long MAX_WALK_ENTRIES = 1_000_000L;

    private final RunWorkspaceLeaseRepository leases;
    private final Path runtimeRoot;
    private final Path resultRoot;
    private final List<String> gitCommand;
    private final Duration gitTimeout;

    public RunWorkspaceService(RunWorkspaceLeaseRepository leases, Path runtimeRoot, Path resultRoot) {
        this(leases, runtimeRoot, resultRoot, List.of("git"), Duration.ofSeconds(GIT_TIMEOUT_SECONDS));
    }

    /**
     * Test seam: the git command prefix and the per-invocation timeout are injectable so that
     * the bounded wait can be proven against a controlled git shim instead of the real git
     * installation on the machine running the tests.
     */
    RunWorkspaceService(RunWorkspaceLeaseRepository leases, Path runtimeRoot, Path resultRoot,
            List<String> gitCommand, Duration gitTimeout) {
        this.leases = Objects.requireNonNull(leases, "Lease repository is required");
        this.gitCommand = List.copyOf(Objects.requireNonNull(gitCommand, "Git command is required"));
        this.gitTimeout = Objects.requireNonNull(gitTimeout, "Git timeout is required");
        try {
            this.runtimeRoot = canonicalDirectory(root(runtimeRoot, "runtime root"));
            this.resultRoot = canonicalDirectory(root(resultRoot, "result root"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ acquire

    @Override
    public WorkspaceLease acquire(ExecutionSpec spec) {
        Objects.requireNonNull(spec, "Execution spec is required");
        Objects.requireNonNull(spec.runId(), "Run id is required");
        AgentExecutionSettings settings = spec.settings();

        if (spec.mode() == ExecutionMode.SANDBOX) {
            return acquireSandbox(spec, settings);
        }
        WorkspaceMode workspaceMode = settings == null ? null : settings.workspaceMode();
        if (workspaceMode == WorkspaceMode.DIRECT) {
            return acquireDirect(spec, settings);
        }
        if (workspaceMode == WorkspaceMode.WORKTREE) {
            return acquireWorktree(spec, settings);
        }
        return acquireScratch(spec);
    }

    private WorkspaceLease acquireDirect(ExecutionSpec spec, AgentExecutionSettings settings) {
        String raw = settings.workspacePath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Direct workspace requires an explicitly selected directory");
        }
        Path selected = Path.of(raw);
        if (!Files.isDirectory(selected)) {
            throw new IllegalArgumentException("Direct workspace directory does not exist: " + raw);
        }
        Path sourceRoot;
        try {
            sourceRoot = selected.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Direct workspace directory does not exist: " + raw, e);
        }
        Path configRoot = configRootUnchecked(spec.runId());
        rejectRuntimeRootInsideSource(sourceRoot);

        LEASE_LOCK.lock();
        try {
            rejectOverlap(sourceRoot, WorkspaceKind.DIRECT);
            writeBaseline(sourceRoot, configRoot.resolve(BASELINE_FILE));
            return persist(spec.runId(), WorkspaceKind.DIRECT, sourceRoot, sourceRoot, configRoot, null, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            LEASE_LOCK.unlock();
        }
    }

    private WorkspaceLease acquireWorktree(ExecutionSpec spec, AgentExecutionSettings settings) {
        String raw = settings.workspacePath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Worktree workspace requires a repository path");
        }
        Path repository = Path.of(raw);
        Path repoRoot = resolveRepository(repository, raw);
        String ref = settings.workspaceBaseRef() == null || settings.workspaceBaseRef().isBlank()
                ? "HEAD" : settings.workspaceBaseRef();
        String commit = resolveCommit(repoRoot, ref, raw);

        Path worktree = runtimeRoot.resolve("worktrees").resolve(spec.runId().toString());
        Path configRoot = configRootUnchecked(spec.runId());
        rejectRuntimeRootInsideSource(repoRoot);

        LEASE_LOCK.lock();
        try {
            rejectOverlap(repoRoot, WorkspaceKind.WORKTREE);
            if (Files.exists(worktree)) {
                throw new IllegalArgumentException("Worktree directory already exists: " + worktree);
            }
            createWorktree(repoRoot, worktree, commit, spec.runId());
            Path localRoot;
            try {
                localRoot = worktree.toRealPath();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return persist(spec.runId(), WorkspaceKind.WORKTREE, localRoot, repoRoot, configRoot, commit, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            LEASE_LOCK.unlock();
        }
    }

    private WorkspaceLease acquireScratch(ExecutionSpec spec) {
        try {
            Path localRoot = canonicalDirectory(createDirectory(runtimeRoot.resolve("scratch").resolve(spec.runId().toString())));
            Path configRoot = configRoot(spec.runId());
            return persist(spec.runId(), WorkspaceKind.SCRATCH, localRoot, null, configRoot, null, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sandbox workspaces consume an admitted snapshot/upload, not a host mount
     * (spec 5.2): the binding records the platform staging root and the admitted
     * source, and no host exclusion lock is taken. Export of reviewable changes
     * belongs to the sandbox backend's {@code exportWorkspace}.
     */
    private WorkspaceLease acquireSandbox(ExecutionSpec spec, AgentExecutionSettings settings) {
        String raw = settings == null ? null : settings.workspacePath();
        Path sourceRoot = null;
        if (raw != null && !raw.isBlank()) {
            Path source = Path.of(raw);
            if (!Files.isDirectory(source)) {
                throw new IllegalArgumentException("Sandbox workspace source directory does not exist: " + raw);
            }
            try {
                sourceRoot = source.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException("Sandbox workspace source directory does not exist: " + raw, e);
            }
            rejectRuntimeRootInsideSource(sourceRoot);
        }
        try {
            Path localRoot = canonicalDirectory(createDirectory(runtimeRoot.resolve("sandbox").resolve(spec.runId().toString())));
            Path configRoot = configRoot(spec.runId());
            return persist(spec.runId(), WorkspaceKind.SANDBOX_SNAPSHOT, localRoot, sourceRoot, configRoot, null, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ capture

    @Override
    public ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend, RuntimeHandle handle, StopProof proof) {
        Objects.requireNonNull(lease, "Workspace lease is required");
        requireVerifiedStopProof(lease.runId(), proof, "capture");
        if (handle == null || !handle.runId().equals(lease.runId())) {
            throw new IllegalArgumentException("Runtime handle run "
                    + (handle == null ? "null" : handle.runId()) + " does not match workspace lease run " + lease.runId());
        }
        Path resultDir = resultRoot.resolve(lease.runId().toString());
        try {
            Files.createDirectories(resultDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            backend.exportWorkspace(handle, resultDir, proof);
        } catch (RuntimeException e) {
            // Export could not be verified: report an incomplete bundle rather than a stable diff.
            return new ArtifactBundle(resultDir, null, false);
        }
        try {
            // Validate links/escapes before any deletion: an unsafe-link refusal must not have
            // side effects, so the artifact tree is checked first and only then scrubbed.
            Map<String, String> manifest = buildManifest(resultDir, true);
            removeSecretBearingFiles(resultDir);
            byte[] manifestBytes = renderManifest(manifest);
            Files.write(resultDir.resolve(MANIFEST_FILE), manifestBytes);
            if (lease.kind() == WorkspaceKind.DIRECT) {
                writeChanges(lease, resultDir, manifest);
            }
            return new ArtifactBundle(resultDir, sha256Hex(manifestBytes), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compares the captured tree with the run's pre-run baseline, so pre-existing user edits are not attributed to the run. */
    private void writeChanges(WorkspaceLease lease, Path resultDir, Map<String, String> manifest) throws IOException {
        if (lease.runtimeRoot() == null) return;
        Path baselineFile = Path.of(lease.runtimeRoot()).resolve(BASELINE_FILE);
        if (!Files.isRegularFile(baselineFile)) return;
        Map<String, String> baseline = parseManifest(Files.readString(baselineFile));
        TreeSet<String> all = new TreeSet<>(baseline.keySet());
        all.addAll(manifest.keySet());
        StringBuilder changes = new StringBuilder();
        for (String rel : all) {
            if (!baseline.containsKey(rel)) {
                changes.append("+ ").append(rel);
            } else if (!manifest.containsKey(rel)) {
                changes.append("- ").append(rel);
            } else if (!baseline.get(rel).equals(manifest.get(rel))) {
                changes.append("~ ").append(rel);
            } else {
                continue;
            }
            changes.append('\n');
        }
        Files.writeString(resultDir.resolve(CHANGES_FILE), changes.toString());
    }

    // ------------------------------------------------------------------ release

    @Override
    public void release(WorkspaceLease lease, StopProof proof) {
        Objects.requireNonNull(lease, "Workspace lease is required");
        LEASE_LOCK.lock();
        try {
            RunWorkspaceLease row = leases.findById(lease.leaseId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Workspace lease " + lease.leaseId() + " is not recorded"));
            // The persisted row is the authority: a caller-supplied record carrying another
            // row's lease id must not unlock that row with its own run's proof.
            requireVerifiedStopProof(row.getRunId(), proof, "release");
            row.setState(RunWorkspaceLease.State.RELEASED);
            row.setReleasedAt(Instant.now());
            leases.save(row);
        } finally {
            LEASE_LOCK.unlock();
        }
    }

    private static void requireVerifiedStopProof(UUID runId, StopProof proof, String action) {
        if (proof == null) {
            throw new IllegalArgumentException(
                    "Workspace " + action + " requires a verified stop proof for run " + runId);
        }
        if (!proof.runId().equals(runId)) {
            throw new IllegalArgumentException("Stop proof run " + proof.runId()
                    + " does not match workspace lease run " + runId);
        }
        if (!proof.allWritersStopped()) {
            throw new IllegalArgumentException(
                    "Stop proof does not verify that all writers stopped for run " + runId);
        }
    }

    // ------------------------------------------------------------------ support

    private WorkspaceLease persist(UUID runId, WorkspaceKind kind, Path localRoot, Path sourceRoot,
            Path configRoot, String baseCommit, boolean retained) {
        RunWorkspaceLease row = RunWorkspaceLease.builder()
                .leaseId(UUID.randomUUID())
                .runId(runId)
                .kind(kind)
                .state(RunWorkspaceLease.State.ACTIVE)
                .localRoot(localRoot.toString())
                .sourceRoot(sourceRoot == null ? null : sourceRoot.toString())
                .runtimeRoot(configRoot.toString())
                .baseCommit(baseCommit)
                .retained(retained)
                .acquiredAt(Instant.now())
                .build();
        leases.save(row);
        return new WorkspaceLease(row.getLeaseId(), runId, kind, localRoot, sourceRoot,
                configRoot.toString(), baseCommit);
    }

    /**
     * One platform writer per genuinely overlapping directory tree; unresolved persisted
     * leases are honoured after a restart. A worktree lease's source root is the shared
     * repository, not a tree it manages, so two worktrees of one repository -- the default
     * coding workflow -- are admitted concurrently; the mutations they do share (the common
     * git directory) are serialized by the platform-owned common-git lock. Every other
     * overlap stays exclusive: a Direct request conflicts with any tree in use, and a second
     * worktree conflicts with a Direct writer on the same canonical tree.
     */
    private void rejectOverlap(Path requestedRoot, WorkspaceKind requestedKind) throws IOException {
        for (RunWorkspaceLease active : leases.findByState(RunWorkspaceLease.State.ACTIVE)) {
            if (requestedKind == WorkspaceKind.WORKTREE && active.getKind() == WorkspaceKind.WORKTREE) {
                continue;
            }
            for (String existing : List.of(active.getLocalRoot(),
                    active.getSourceRoot() == null ? active.getLocalRoot() : active.getSourceRoot())) {
                Path existingPath = Path.of(existing);
                if (!Files.exists(existingPath)) continue;
                if (WorkspacePaths.overlaps(requestedRoot, existingPath)) {
                    throw new IllegalArgumentException("Workspace conflict: " + requestedRoot
                            + " overlaps active lease " + active.getLeaseId() + " (run " + active.getRunId() + ")");
                }
            }
        }
    }

    private Path resolveRepository(Path repository, String raw) {
        if (!Files.isDirectory(repository)) {
            throw new IllegalArgumentException("Worktree workspace requires a git repository: " + raw);
        }
        GitResult probe;
        try {
            probe = git(repository, "rev-parse", "--git-dir");
        } catch (IOException e) {
            throw new IllegalArgumentException("Worktree workspace requires a git repository: " + raw, e);
        }
        if (probe.exitCode() != 0) {
            throw new IllegalArgumentException("Worktree workspace requires a git repository: " + raw);
        }
        try {
            return repository.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Worktree workspace requires a git repository: " + raw, e);
        }
    }

    private String resolveCommit(Path repository, String ref, String raw) {
        if (ref.startsWith("-")) {
            // Git refnames cannot begin with a dash; rejecting the shape up front keeps the
            // operator string out of git's option parser entirely.
            throw new IllegalArgumentException("Cannot resolve worktree base ref '" + ref + "' in " + raw);
        }
        GitResult result;
        try {
            result = git(repository, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve worktree base ref '" + ref + "' in " + raw, e);
        }
        String commit = result.output().trim();
        // Only a full lowercase hexadecimal commit is accepted: any other stdout is not a commit.
        if (result.exitCode() != 0 || !COMMIT_SHA.matcher(commit).matches()) {
            throw new IllegalArgumentException("Cannot resolve worktree base ref '" + ref + "' in " + raw);
        }
        return commit;
    }

    /** Creates the run-owned worktree from a resolved commit; explicit argv only, never an interpolated shell string. */
    private void createWorktree(Path repository, Path worktree, String commit, UUID runId) throws IOException {
        Files.createDirectories(worktree.getParent());
        GitResult result = WorkspacePaths.withCommonGitLock(() -> git(repository,
                "worktree", "add", "--detach", worktree.toString(), commit));
        if (result.exitCode() != 0) {
            // Link-safe cleanup of the failed worktree, validated against the canonical
            // platform runtime root: a junction planted at (or above) the worktree parent
            // makes the deletion refuse instead of deleting inside the link's target; a
            // junction planted under the failed worktree is removed as the entry itself.
            WorkspacePaths.deleteTree(runtimeRoot, worktree);
            throw new IllegalStateException("git worktree add failed for run " + runId + ": " + result.output());
        }
    }

    private GitResult git(Path repository, String... args) throws IOException {
        List<String> argv = new ArrayList<>(gitCommand);
        argv.add("-C");
        argv.add(repository.toString());
        argv.addAll(List.of(args));
        return runGit(argv);
    }

    /**
     * Runs git with the wait always bounded by {@link #gitTimeout}, reading the child's output
     * only after that wait. Reading the stdout pipe before waiting (the earlier shape) blocks
     * for as long as the child keeps stdout open, so a stalled git hung indefinitely — and the
     * mutating calls run under the lease lock and the common-git lock, so one stalled git
     * wedged every later acquisition until a restart. Output is redirected to a file instead,
     * the process tree is destroyed on expiry, and the timeout is reported with whatever
     * output had already been written.
     */
    private GitResult runGit(List<String> argv) throws IOException {
        Path outputFile = Files.createTempFile("aria-git-", ".log");
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());
            process = pb.start();
            if (!process.waitFor(gitTimeout.toSeconds(), TimeUnit.SECONDS)) {
                destroyTree(process);
                String partial = readOutputOrEmpty(outputFile);
                throw new IOException("git timed out after " + gitTimeout.toSeconds() + "s: " + argv
                        + (partial.isBlank() ? "" : " (partial output: " + partial.strip() + ")"));
            }
            return new GitResult(process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            if (process != null) {
                destroyTree(process);
            }
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + argv, e);
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException e) {
                // A forcibly destroyed child can still hold the handle on Windows; leaking the
                // temp file is preferable to failing an otherwise completed git invocation.
            }
        }
    }

    /** Kills a stalled git and anything it spawned (a shell wrapper would hold the output handle). */
    private static void destroyTree(Process process) {
        // Snapshot the descendants first: once the parent is dead the tree can no longer be walked.
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    private static String readOutputOrEmpty(Path outputFile) {
        try {
            return Files.readString(outputFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ----------------------------------------------------------------- artifacts

    /**
     * Removes generated secret-bearing files from the exported tree. The walk is bounded
     * ({@link #walkBounded}) and never descends into a link or junction, and a candidate
     * is deleted only when its real path stays inside the verified artifact root, so no
     * deletion can occur outside it.
     *
     * <p>An entry that does not resolve -- a broken symbolic link or a dangling directory
     * junction -- is its own case, whatever its name: it holds no content and has no
     * target a delete could reach, so it is skipped as the tree was exported -- left in
     * the result, where its presence records it -- instead of letting the resolution
     * failure abort the whole capture. A broken symbolic link is reported to
     * {@code visitFile}; a dangling junction cannot be opened as a directory at all, so
     * the walker reports it to {@code visitFileFailed} first, which skips it by the same
     * rule. An escaping link is still refused, unchanged, by the strict manifest walk
     * that runs before any deletion.
     */
    private static void removeSecretBearingFiles(Path root) throws IOException {
        Path rootReal = root.toRealPath();
        List<Path> doomed = new ArrayList<>();
        walkBounded(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return WorkspacePaths.isLinkLikeEntry(dir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isSecretBearing(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Path real;
                try {
                    real = file.toRealPath();
                } catch (IOException e) {
                    // Unresolvable secret-named link: skipped as itself, never followed
                    // and never deleted; there is no content to leak and no target to touch.
                    return FileVisitResult.CONTINUE;
                }
                if (real.startsWith(rootReal) && Files.isRegularFile(real)) {
                    doomed.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // A dangling junction never reaches visitFile -- the walker fails to open it
                // as a directory and reports it here first -- so an unresolvable entry is
                // skipped by the same rule as above, of any name. Every other failure propagates.
                if (isUnresolvableEntry(file)) {
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }
        });
        for (Path file : doomed) {
            Files.delete(file);
        }
    }

    /**
     * True for an entry that exists as itself but does not resolve anywhere: a broken
     * symbolic link or a dangling directory junction. Such an entry has no resolvable
     * target, so it can neither carry content nor be reached by a deletion; the walkers
     * report it through {@code visitFileFailed} (junctions) or resolve-failure (links).
     */
    private static boolean isUnresolvableEntry(Path entry) {
        return Files.exists(entry, LinkOption.NOFOLLOW_LINKS) && !Files.exists(entry);
    }

    private static boolean isSecretBearing(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return SECRET_FILE_NAMES.contains(lower)
                || SECRET_FILE_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /**
     * Bounded {@code walkFileTree}: the single walk entry point of this service. A walk is
     * bounded in two independent ways. Structurally, a link-like directory -- a symbolic
     * link, a Windows directory junction, or any entry whose real path its parent does not
     * imply ({@link WorkspacePaths#isLinkLikeEntry}) -- is never descended, so a
     * self-referential junction cannot expand the walk until the OS path limit; the
     * delegate still sees the entry first, so a caller can refuse it (the escape refusal)
     * before it is skipped. Numerically, the walk refuses to go deeper than
     * {@code maxDepth} levels or to visit more than {@code maxEntries} entries below its
     * root, failing with a clear message instead of walking forever even if some link
     * shape escaped the structural check. The budgets are parameters so tests can prove
     * the bounding with small budgets; production walks use the class constants.
     */
    static void walkBounded(Path root, FileVisitor<Path> visitor) throws IOException {
        walkBounded(root, MAX_WALK_DEPTH, MAX_WALK_ENTRIES, visitor);
    }

    static void walkBounded(Path root, int maxDepth, long maxEntries, FileVisitor<Path> visitor) throws IOException {
        Path rootAbs = root.toAbsolutePath().normalize();
        long[] visited = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root)) {
                    checkWalkBudget(rootAbs, dir, ++visited[0], maxDepth, maxEntries);
                }
                FileVisitResult decision = visitor.preVisitDirectory(dir, attrs);
                if (!dir.equals(root) && WorkspacePaths.isLinkLikeEntry(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return decision;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkWalkBudget(rootAbs, file, ++visited[0], maxDepth, maxEntries);
                return visitor.visitFile(file, attrs);
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                checkWalkBudget(rootAbs, file, ++visited[0], maxDepth, maxEntries);
                return visitor.visitFileFailed(file, exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return visitor.postVisitDirectory(dir, exc);
            }
        });
    }

    private static void checkWalkBudget(Path rootAbs, Path entry, long visited, int maxDepth, long maxEntries)
            throws IOException {
        if (visited > maxEntries) {
            throw new IOException("Workspace walk exceeded the entry budget of " + maxEntries
                    + " entries at " + entry);
        }
        if (rootAbs.relativize(entry.toAbsolutePath().normalize()).getNameCount() > maxDepth) {
            throw new IOException("Workspace walk exceeded the depth budget of " + maxDepth
                    + " levels at " + entry);
        }
    }

    private static String relativePath(Path rootReal, Path entry) {
        return rootReal.relativize(entry.toAbsolutePath().normalize())
                .toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * Content-addressed manifest of the exported tree. {@code strictLinks}
     * rejects links that resolve outside the artifact directory (an export must
     * not smuggle host files); the pre-run baseline walk is lenient so a
     * pre-existing link in a user directory does not block admission.
     *
     * <p>Every walk is bounded ({@link #walkBounded}): a link-like directory is
     * never descended, so a self-referential junction -- the legacy Windows
     * "Application Data" loop real user directories contain -- cannot expand the
     * walk, and a depth/entry budget turns any remaining pathological tree into
     * an explicit refusal. A resolvable alias that leaves the artifact root keeps
     * its exact refusal (strict mode) or its silent exclusion (lenient mode); an
     * alias inside the root is skipped and its content is hashed once, under its
     * real path, never a second time under the alias.
     *
     * <p>An unresolvable entry of any name -- a broken symbolic link or a
     * dangling directory junction -- is skipped as its own case and left in the
     * tree exactly as exported: it cannot carry content and has no target that
     * could be reached. Only entries that resolve are subject to the escape
     * refusal below.
     */
    private static Map<String, String> buildManifest(Path root, boolean strictLinks) throws IOException {
        Path rootReal = root.toRealPath();
        Map<String, String> manifest = new TreeMap<>();
        List<Path> entries = new ArrayList<>();
        walkBounded(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (WorkspacePaths.isLinkLikeEntry(dir)) {
                    // An alias is never descended (the bounded walk enforces this too). A
                    // resolvable alias that resolves outside the artifact root keeps its
                    // exact refusal in strict mode; a dangling alias cannot open as a
                    // directory at all and is reported to visitFileFailed instead.
                    Path real;
                    try {
                        real = dir.toRealPath();
                    } catch (IOException e) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!real.startsWith(rootReal)) {
                        if (!strictLinks) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        throw new IllegalArgumentException(
                                "Unsafe link escapes artifact directory: " + relativePath(rootReal, dir));
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                entries.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                entries.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // A dangling junction is reported here (the walker fails to open it as a
                // directory); an entry that exists as itself but resolves nowhere is skipped
                // as its own case -- of any name, exactly like the scrub walk -- and left in
                // the tree as exported. Every other failure propagates.
                if (isUnresolvableEntry(file)) {
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }
        });
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (MANIFEST_FILE.equals(name) || CHANGES_FILE.equals(name) || BASELINE_FILE.equals(name)) continue;
            if (isSecretBearing(name)) continue;
            String rel = relativePath(rootReal, entry);
            if (rel.isEmpty() || rel.startsWith("..") || Path.of(rel).isAbsolute()) {
                throw new IllegalArgumentException("Unsafe artifact path: " + name);
            }
            // Resolves symlinks and junctions: anything pointing outside the artifact root
            // would smuggle host files into the retained bundle.
            Path real;
            try {
                real = entry.toRealPath();
            } catch (IOException e) {
                // An entry that exists as itself but does not resolve (a broken symbolic
                // link of any name) is skipped as its own case, like the skipped junction.
                if (isUnresolvableEntry(entry)) {
                    continue;
                }
                throw e;
            }
            if (!real.startsWith(rootReal)) {
                if (!strictLinks) continue;
                throw new IllegalArgumentException("Unsafe link escapes artifact directory: " + rel);
            }
            if (Files.isRegularFile(real)) {
                manifest.put(rel, sha256Hex(Files.readAllBytes(real)));
            }
        }
        return manifest;
    }

    private static byte[] renderManifest(Map<String, String> manifest) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            builder.append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseManifest(String content) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            int separator = line.indexOf("  ");
            if (separator <= 0) continue;
            parsed.put(line.substring(separator + 2), line.substring(0, separator));
        }
        return parsed;
    }

    private void writeBaseline(Path sourceRoot, Path baselineFile) throws IOException {
        Files.createDirectories(baselineFile.getParent());
        Files.write(baselineFile, renderManifest(buildManifest(sourceRoot, false)));
    }

    private Path configRoot(UUID runId) throws IOException {
        return canonicalDirectory(createDirectory(runtimeRoot.resolve("runs").resolve(runId.toString())));
    }

    private Path configRootUnchecked(UUID runId) {
        try {
            return configRoot(runId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void rejectRuntimeRootInsideSource(Path sourceRoot) {
        if (runtimeRoot.startsWith(sourceRoot) || resultRoot.startsWith(sourceRoot)
                || sourceRoot.startsWith(runtimeRoot) || sourceRoot.startsWith(resultRoot)) {
            throw new IllegalArgumentException(
                    "Platform runtime root must be outside the source workspace: " + sourceRoot);
        }
    }

    private static Path root(Path path, String what) {
        return Objects.requireNonNull(path, what + " is required").toAbsolutePath().normalize();
    }

    private static Path createDirectory(Path path) throws IOException {
        return Files.createDirectories(path);
    }

    private static Path canonicalDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException("Not a directory: " + path);
        }
        return path.toRealPath();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record GitResult(int exitCode, String output) {
    }
}
