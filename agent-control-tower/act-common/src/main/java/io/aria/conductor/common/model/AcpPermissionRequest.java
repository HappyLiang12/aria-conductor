package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Companion record for an ACP {@code request_permission} ask: exactly one row per
 * {@link Approval} whose source is {@code ACP_PERMISSION}. It shares the approval id as its
 * primary key and carries the bridge correlation plus the ask payload the review surfaces render.
 * <p>
 * Two deliberate shape choices:
 * <ul>
 *   <li>The table name stays singular ({@code acp_permission_request}) per the frozen design
 *       contract, even though most tables in this schema are plural.</li>
 *   <li>References to other aggregates are plain UUID columns (no {@code @ManyToOne}), matching
 *       the sibling entities — the companion is written in the same transaction as its approval
 *       and is only ever read through the approval it belongs to.</li>
 * </ul>
 * {@code deliveryState} is intentionally a plain String: the state machine that gives it meaning
 * belongs to the ACP decision-dispatch slice, and this record only persists what it is handed.
 */
@Entity
@Table(name = "acp_permission_request",
        uniqueConstraints = @UniqueConstraint(name = "uq_acp_permission_correlation",
                columnNames = {"bridge_session_id", "bridge_request_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcpPermissionRequest {

    @Id
    @Column(name = "approval_id", columnDefinition = "UUID")
    private UUID approvalId;

    @Column(name = "run_id", nullable = false, columnDefinition = "UUID")
    private UUID runId;

    @Column(name = "agent_id", columnDefinition = "UUID")
    private UUID agentId;

    @Column(name = "bridge_session_id", nullable = false, length = 128)
    private String bridgeSessionId;

    @Column(name = "bridge_request_id", nullable = false, length = 128)
    private String bridgeRequestId;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "tool_name", nullable = false, length = 256)
    private String toolName;

    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "request_digest", nullable = false, length = 128)
    private String requestDigest;

    @Column(name = "display_json", columnDefinition = "TEXT")
    private String displayJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "delivery_state", nullable = false, length = 32)
    private String deliveryState;

    @Column(name = "selected_option_id", length = 64)
    private String selectedOptionId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
