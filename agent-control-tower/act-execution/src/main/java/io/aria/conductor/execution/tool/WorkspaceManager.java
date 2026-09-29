package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.RunWorkspaceLease;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.execution.repository.RunWorkspaceLeaseRepository;
import io.aria.conductor.execution.runtime.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Per-run isolated workspace manager.
 * Provisions a fresh directory per run under a writable root; path-jails all file operations;
 * cleans up on terminal state. Orphan directories are swept periodically.
 *
 * <p>When the run workspace lease ledger is wired (spec 5.2), directory removal is
 * ownership-aware and evidence-based: a directory that belongs to a Direct lease or to a
 * retained worktree is never removed, and neither {@link #cleanup(UUID)} nor the age-based
 * sweeper removes a leased workspace without the recorded stop evidence — an
 * unresolved (active) lease, a retained lease and a directory the ledger does not know all
 * survive both. Without a ledger the legacy age-based scratch contract is unchanged.
 */
@Slf4j
@Component
public class WorkspaceManager {

    private final Path root;
    /** Lease ledger used to positively identify platform-owned scratch; null in legacy construction. */
    private final RunWorkspaceLeaseRepository leases;

    public WorkspaceManager(String workspaceDir) {
        this(workspaceDir, (RunWorkspaceLeaseRepository) null);
    }

    @Autowired
    public WorkspaceManager(@Value("${tools.file.workspace-dir:./data/workspaces}") String workspaceDir,
                            ObjectProvider<RunWorkspaceLeaseRepository> leases) {
        this(workspaceDir, leases.getIfAvailable());
    }

    public WorkspaceManager(String workspaceDir, RunWorkspaceLeaseRepository leases) {
        // Resolve the root robustly (#26): prefer the TOOLS_FILE_WORKSPACE_DIR env var explicitly,
        // falling back to the injected property. @Value does not perform @ConfigurationProperties-style
        // relaxed binding, so reading the env var directly removes any binding ambiguity. The default
        // is CWD-relative, so log the resolved absolute path to make the effective location observable.
        String envDir = System.getenv("TOOLS_FILE_WORKSPACE_DIR");
        String effective = (envDir != null && !envDir.isBlank()) ? envDir : workspaceDir;
        Path spelled = Path.of(effective).toAbsolutePath().normalize();
        Path adopted = spelled;
        try {
            Files.createDirectories(spelled);
            // The platform-owned root is adopted canonically at construction: every later
            // deletion is validated against this real path, so a junction planted at (or
            // above) the root afterwards cannot redirect cleanup into its target.
            adopted = spelled.toRealPath();
            log.info("Workspace root resolved to: {}", adopted);
        } catch (IOException e) {
            log.error("Could not create workspace root {} — per-run provisioning will fail: {}", spelled, e.getMessage());
        }
        this.root = adopted;
        this.leases = leases;
    }

    /**
     * Lazily provision a workspace for the given run. Returns the absolute workspace path.
     * Idempotent — safe to call multiple times for the same run.
     */
    public String provision(UUID runId) {
        return getOrProvision(runId);
    }

    /**
     * Contract entry point shared by the loop-level pre-provision and the tool-execution use-site
     * (#26): return the run's workspace path, creating it if absent. Idempotent.
     */
    public String getOrProvision(UUID runId) {
        Path dir = root.resolve(runId.toString());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to provision workspace for run {} under root {}: {}", runId, root, e.getMessage());
            throw new RuntimeException("Workspace provisioning failed for run " + runId, e);
        }
        return dir.toString();
    }

    /**
     * Resolve a relative path within the run's workspace, enforcing path-jail.
     * Rejects absolute paths and traversal (..) that escape the workspace, and refuses a
     * link component (a symbolic link or a Windows directory junction -- the shapes the
     * agent's own shell tool can plant) whose real path leaves the workspace: the jail is
     * physical, not lexical. The per-run directory itself must be the platform-created
     * directory reached from the adopted root, so a link pre-planted at the run workspace
     * cannot redirect a file tool.
     *
     * @throws SecurityException if the resolved path escapes the workspace
     */
    public Path resolve(UUID runId, String relativePath) {
        Path workspace = root.resolve(runId.toString()).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        verifyPhysicalJail(workspace, resolved, relativePath);
        return resolved;
    }

    /**
     * The physical jail: the deepest existing ancestor of the requested path must resolve
     * to a real path inside the workspace. The run directory must be the platform-created
     * directory below the adopted root (its real path must be exactly what the canonical
     * parent implies); the requested path is judged by its deepest existing ancestor, so a
     * link planted anywhere on the chain is caught even when the leaf does not exist yet;
     * and an entry that does not resolve at all (a dangling link, whose creation a write
     * would follow to its target) is refused as well.
     */
    private void verifyPhysicalJail(Path workspace, Path resolved, String relativePath) {
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)
                && !WorkspacePaths.reachedThroughCanonicalParents(root, workspace)) {
            throw new SecurityException("Path escapes workspace through a planted link at the run directory "
                    + workspace + ": " + relativePath);
        }
        Path ancestor = resolved;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            // No existing component at all (the workspace root itself is gone): nothing can be
            // shown to stay inside the jail, so the path is refused like the lexical escapes.
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        Path ancestorReal;
        try {
            ancestorReal = ancestor.toRealPath();
        } catch (IOException e) {
            throw new SecurityException("Path escapes workspace through an unresolvable link at "
                    + ancestor + ": " + relativePath);
        }
        Path jail = Files.exists(workspace, LinkOption.NOFOLLOW_LINKS) ? workspace : root;
        if (!ancestorReal.startsWith(jail)) {
            throw new SecurityException("Path escapes workspace through a link at "
                    + firstLinkComponent(jail, ancestor) + ": " + relativePath);
        }
    }

    /**
     * The first component below the (canonical) jail whose real path its canonical parent
     * does not imply: the planted link that redirects the path outside. Falls back to the
     * anchor itself when the anchor is not below the jail (a redirected root).
     */
    private static Path firstLinkComponent(Path jail, Path anchor) {
        if (!anchor.startsWith(jail)) {
            return anchor;
        }
        Path real = jail;
        for (Path component : jail.relativize(anchor)) {
            Path spelled = real.resolve(component.getFileName());
            Path resolvedComponent;
            try {
                resolvedComponent = spelled.toRealPath();
            } catch (IOException e) {
                return spelled;
            }
            if (!resolvedComponent.equals(spelled)) {
                return spelled;
            }
            real = resolvedComponent;
        }
        return anchor;
    }

    /**
     * Get the workspace directory for a run (without provisioning).
     * Returns null if the workspace does not exist.
     */
    public String getIfExists(UUID runId) {
        Path dir = root.resolve(runId.toString());
        return Files.isDirectory(dir) ? dir.toString() : null;
    }

    /**
     * Clean up the workspace for a completed/failed/cancelled run.
     *
     * <p>With a lease ledger wired, a leased workspace is removed only on recorded stop
     * evidence: every lease of the run must be a {@code SCRATCH} workspace in state
     * {@code RELEASED} (written only by the proof-gated {@code release}) that carries no
     * retention. A Direct user directory, a worktree, an active lease and a retained
     * workspace are all left in place — removing them is a distinct operator action, and
     * the caller's claim that the run stopped is not evidence. A directory with no lease
     * row is not leased by the platform workspace service (legacy engine provisioning) and
     * keeps the legacy explicit terminal-cleanup contract.
     */
    public void cleanup(UUID runId) {
        Path dir = root.resolve(runId.toString());
        if (!Files.exists(dir)) return;
        if (!removableLeasedWorkspace(runId)) {
            log.info("Skipped cleanup for run {}: the workspace has no released, unretained scratch lease", runId);
            return;
        }
        try {
            deleteRecursively(dir);
            log.info("Cleaned up workspace for run {}", runId);
        } catch (IOException e) {
            log.warn("Failed to cleanup workspace for run {}: {}", runId, e.getMessage());
        }
    }

    /**
     * True when the directory may be removed: without a ledger the legacy contract applies;
     * with one, either the run holds no lease at all or every lease it holds is a stopped,
     * unretained scratch workspace. {@code RELEASED} is the recorded stop evidence — set
     * only by a proof-verified release.
     */
    private boolean removableLeasedWorkspace(UUID runId) {
        if (leases == null) return true;
        return leases.findByRunId(runId).stream().allMatch(WorkspaceManager::isStoppedUnretainedScratch);
    }

    /** The only lease shape an automatic removal may delete: platform-owned scratch, stopped, unretained. */
    private static boolean isStoppedUnretainedScratch(RunWorkspaceLease lease) {
        return lease.getKind() == WorkspaceKind.SCRATCH
                && lease.getState() == RunWorkspaceLease.State.RELEASED
                && !lease.isRetained();
    }

    /**
     * Periodic sweeper: remove orphaned platform-owned scratch directories older than 2 hours.
     * Mirrors the ApprovalExpiryChecker pattern.
     *
     * <p>With a lease ledger wired the sweep is ownership-aware (spec 5.2): a directory is
     * removed only when the run it is named after owns at least one lease and every one of
     * its leases is a stopped ({@code RELEASED}), unretained scratch workspace. Direct
     * directories, retained worktrees, still-active leases, platform directories the ledger
     * does not know and directories that are not run directories all survive. Without a
     * ledger the legacy age-based scratch behaviour is unchanged.
     */
    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void sweepOrphanWorkspaces() {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minusSeconds(7200); // 2 hours
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
                    if (!attrs.lastModifiedTime().toInstant().isBefore(cutoff)) return;
                    if (leases != null && !isSweepableOrphan(dir)) {
                        log.debug("Sweep kept {}: no stopped, unretained scratch lease owns it", dir.getFileName());
                        return;
                    }
                    deleteRecursively(dir);
                    log.info("Swept orphan workspace: {}", dir.getFileName());
                } catch (IOException e) {
                    log.debug("Could not inspect workspace dir {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Workspace sweep skipped: {}", e.getMessage());
        }
    }

    /**
     * Positive ownership for the tree the sweeper walks (spec 5.2). This root is
     * platform-owned by construction and holds one directory per run, named by the run id,
     * so the ledger row for that run id — not path equality with a lease's {@code local_root},
     * which lives under the platform runtime root — is the ownership evidence for the
     * directory. The directory is sweepable only when the run owns at least one lease and
     * every lease is a stopped (RELEASED), unretained scratch workspace.
     */
    private boolean isSweepableOrphan(Path dir) {
        UUID runId;
        try {
            runId = UUID.fromString(dir.getFileName().toString());
        } catch (IllegalArgumentException e) {
            return false; // not a run directory: no ownership evidence
        }
        List<RunWorkspaceLease> rows = leases.findByRunId(runId);
        return !rows.isEmpty() && rows.stream().allMatch(WorkspaceManager::isStoppedUnretainedScratch);
    }

    private void deleteRecursively(Path dir) throws IOException {
        // Link-safe deletion from the canonical platform-owned root: a junction replacing
        // the root -- or sitting anywhere on the chain between it and the deletion root --
        // fails the canonical-parent validation and the deletion is refused, so cleanup and
        // the sweeper can never be redirected into the link's target. Below that, a junction
        // inside the owned tree is removed as the entry itself and never descended into.
        WorkspacePaths.deleteTree(root, dir);
    }
}
