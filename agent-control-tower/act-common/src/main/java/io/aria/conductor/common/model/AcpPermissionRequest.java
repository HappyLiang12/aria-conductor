package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The run-scoped permission ledger (spec §6.3): one row per normalized native
 * permission request and one row per one-use write grant, so a run's permission
 * decisions never live only in a process that can restart.
 *
 * <p>Correlation is unique on {@code (run_id, session_id, request_id)}: the row
 * exists before the ask is displayed, and a changed payload for the same
 * correlation is rejected instead of being coerced. A write grant uses the
 * platform boundary's own correlation key ({@code platform-mcp} /
 * {@code grant:<arguments digest>}), which makes a second outstanding
 * authorization for the identical call impossible.
 *
 * <p>{@code target} and {@code delivery_state} are stored as plain names because
 * the corresponding enums are execution-module types: this module is the shared
 * kernel and never depends downstream. {@code arguments_digest} is the hex
 * SHA-256 of the argument document and {@code options_json} the offered decision
 * options — persisted before display, never derived from an option's position.
 */
@Entity
@Table(name = "acp_permission_request", indexes = {
        @Index(name = "idx_acp_permission_run", columnList = "run_id"),
        @Index(name = "idx_acp_permission_approval", columnList = "approval_id")
}, uniqueConstraints = @UniqueConstraint(name = "uq_acp_permission_correlation",
        columnNames = {"run_id", "session_id", "request_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcpPermissionRequest {

    /** Ledger row kinds. */
    public enum Kind {
        /** A normalized permission request raised by a core session or the platform boundary. */
        NATIVE_PERMISSION,
        /** A one-use authorization for exactly one matching platform MCP call. */
        WRITE_GRANT
    }

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    /** The correlated approval row; null for a grant recorded without one. */
    @Column(name = "approval_id", columnDefinition = "UUID")
    private UUID approvalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private Kind kind;

    @Column(name = "run_id", nullable = false, columnDefinition = "UUID")
    private UUID runId;

    /** Runtime session id; {@code platform-mcp} for a platform-boundary grant. */
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** Native request id; {@code grant:<arguments digest>} for a grant. */
    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "tool_name", nullable = false, length = 256)
    private String toolName;

    /** {@code NATIVE_TOOL} or {@code PLATFORM_MCP}, as normalized for storage. */
    @Column(name = "target", nullable = false, length = 32)
    private String target;

    @Column(name = "arguments_digest", nullable = false, length = 128)
    private String argumentsDigest;

    @Column(name = "arguments_json", columnDefinition = "TEXT")
    private String argumentsJson;

    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** A {@link io.aria.conductor.execution.approval.PermissionDeliveryState} name. */
    @Column(name = "delivery_state", nullable = false, length = 32)
    private String deliveryState;

    @Column(name = "selected_option_id", length = 64)
    private String selectedOptionId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** Set exactly once when a write grant is consumed. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
