package io.aria.conductor.common.model;

import io.aria.conductor.common.runtime.WorkspaceKind;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted ownership record of one run's workspace lease (spec 5.2): the
 * canonical platform-owned runtime root, the user-owned source root (never
 * recursively removed by the platform) and the resolved base commit.
 *
 * <p>The record is the positive-ownership evidence cleanup relies on: an
 * age-based sweeper may only remove a directory whose lease is a released,
 * unretained {@code SCRATCH}; {@code DIRECT} roots belong to the user and
 * {@code WORKTREE} roots are retained for review until a distinct operator
 * action removes them. {@code state} tracks locking, {@code retained} tracks
 * retention policy -- releasing a lease never discards a retained workspace.
 */
@Entity
@Table(name = "run_workspace_leases", indexes = {
        @Index(name = "idx_run_workspace_leases_run", columnList = "run_id"),
        @Index(name = "idx_run_workspace_leases_state", columnList = "state")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunWorkspaceLease {

    /** Lease lifecycle. {@code RELEASED} is only reached with a verified stop proof. */
    public enum State {
        ACTIVE,
        RELEASED
    }

    @Id
    @Column(name = "lease_id", columnDefinition = "UUID")
    private UUID leaseId;

    @Column(name = "run_id", nullable = false, columnDefinition = "UUID")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_kind", nullable = false, length = 32)
    private WorkspaceKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    /** Canonical (symlink/junction-resolved) working directory of the lease. */
    @Column(name = "local_root", nullable = false, columnDefinition = "TEXT")
    private String localRoot;

    /** Canonical user-owned source root; null for a platform-owned scratch workspace. */
    @Column(name = "source_root", columnDefinition = "TEXT")
    private String sourceRoot;

    /** Platform-owned run configuration root, outside the source/worktree. */
    @Column(name = "runtime_root", columnDefinition = "TEXT")
    private String runtimeRoot;

    /** Resolved commit the worktree was created from; null for other kinds. */
    @Column(name = "base_commit", length = 64)
    private String baseCommit;

    /** Retention policy: retained workspaces are never removed by automatic cleanup. */
    @Column(name = "retained", nullable = false)
    private boolean retained;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
