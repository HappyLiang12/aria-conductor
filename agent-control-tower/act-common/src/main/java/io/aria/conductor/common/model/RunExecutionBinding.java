package io.aria.conductor.common.model;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, run-owned execution binding (spec 3.2): the resolved core, mode,
 * non-secret settings snapshot, workspace ownership, credential reference,
 * configuration revision and deadline frozen before a run launches, extended
 * with observed runtime identity/state, CLI/protocol version and usage once
 * established.
 *
 * <p>The run UUID is both the key and the ownership edge: the running task
 * reads this row instead of re-resolving the agent's mutable settings, and
 * agent edits therefore affect only later runs. Legacy runs have no row and do
 * not receive an invented historical core snapshot.
 *
 * <p>Never stores secrets. {@code credentialRef} is a reference (resolved by
 * the credential service at launch), and secret environments live only in the
 * memory-only runtime carriers ({@code SecretBundle}/{@code LaunchProfile}).
 * Unknown usage stays NULL -- it is not zero.
 */
@Entity
@Table(name = "run_execution_bindings", indexes = {
        @Index(name = "idx_run_bindings_agent", columnList = "agent_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunExecutionBinding {

    @Id
    @Column(name = "run_id", columnDefinition = "UUID")
    private UUID runId;

    @Column(name = "agent_id", nullable = false, columnDefinition = "UUID")
    private UUID agentId;

    @Column(name = "core_id", nullable = false, length = 64)
    private String coreId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 16)
    private ExecutionMode executionMode;

    /** Serialized {@code AgentExecutionSettings} snapshot -- non-secret data only. */
    @Column(name = "settings_json", nullable = false, columnDefinition = "TEXT")
    private String settingsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_kind", length = 32)
    private WorkspaceKind workspaceKind;

    @Column(name = "workspace_lease_id", columnDefinition = "UUID")
    private UUID workspaceLeaseId;

    @Column(name = "workspace_root", columnDefinition = "TEXT")
    private String workspaceRoot;

    @Column(name = "workspace_source_root", columnDefinition = "TEXT")
    private String workspaceSourceRoot;

    @Column(name = "workspace_base_commit", length = 255)
    private String workspaceBaseCommit;

    /** Reference resolved by the credential service -- never the secret value. */
    @Column(name = "credential_ref", length = 255)
    private String credentialRef;

    @Column(name = "configuration_revision", length = 128)
    private String configurationRevision;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "runtime_environment_id", length = 255)
    private String runtimeEnvironmentId;

    @Column(name = "runtime_ownership_identity", length = 255)
    private String runtimeOwnershipIdentity;

    @Column(name = "runtime_endpoint", length = 1024)
    private String runtimeEndpoint;

    /** Observed CLI/protocol version, recorded when established. */
    @Column(name = "runtime_version", length = 128)
    private String runtimeVersion;

    @Column(name = "runtime_state", length = 32)
    private String runtimeState;

    /** Known usage stays NULL while unobserved; unknown usage is not zero. */
    @Column(name = "usage_input_tokens")
    private Long usageInputTokens;

    @Column(name = "usage_output_tokens")
    private Long usageOutputTokens;

    @Column(name = "usage_credits", precision = 18, scale = 6)
    private BigDecimal usageCredits;

    @Column(name = "observed_model", length = 128)
    private String observedModel;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
