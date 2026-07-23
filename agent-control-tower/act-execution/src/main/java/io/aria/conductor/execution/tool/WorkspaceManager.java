package io.aria.conductor.execution.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Per-run isolated workspace manager.
 * Provisions a fresh directory per run under a writable root; path-jails all file operations;
 * cleans up on terminal state. Orphan directories are swept periodically.
 */
@Slf4j
@Component
public class WorkspaceManager {

    private final Path root;

    public WorkspaceManager(@Value("${tools.file.workspace-dir:./data/workspaces}") String workspaceDir) {
        // Resolve the root robustly (#26): prefer the TOOLS_FILE_WORKSPACE_DIR env var explicitly,
        // falling back to the injected property. @Value does not perform @ConfigurationProperties-style
        // relaxed binding, so reading the env var directly removes any binding ambiguity. The default
        // is CWD-relative, so log the resolved absolute path to make the effective location observable.
        String envDir = System.getenv("TOOLS_FILE_WORKSPACE_DIR");
        String effective = (envDir != null && !envDir.isBlank()) ? envDir : workspaceDir;
        this.root = Path.of(effective).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("Workspace root resolved to: {}", root);
        } catch (IOException e) {
            log.error("Could not create workspace root {} — per-run provisioning will fail: {}", root, e.getMessage());
        }
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
     * Rejects absolute paths and traversal (..) that escape the workspace.
     *
     * @throws SecurityException if the resolved path escapes the workspace
     */
    public Path resolve(UUID runId, String relativePath) {
        Path workspace = root.resolve(runId.toString()).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        return resolved;
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
     */
    public void cleanup(UUID runId) {
        Path dir = root.resolve(runId.toString());
        if (!Files.exists(dir)) return;
        try {
            deleteRecursively(dir);
            log.info("Cleaned up workspace for run {}", runId);
        } catch (IOException e) {
            log.warn("Failed to cleanup workspace for run {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Periodic sweeper: remove orphan workspace directories older than 2 hours.
     * Mirrors the ApprovalExpiryChecker pattern.
     */
    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void sweepOrphanWorkspaces() {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minusSeconds(7200); // 2 hours
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        deleteRecursively(dir);
                        log.info("Swept orphan workspace: {}", dir.getFileName());
                    }
                } catch (IOException e) {
                    log.debug("Could not inspect workspace dir {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Workspace sweep skipped: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
