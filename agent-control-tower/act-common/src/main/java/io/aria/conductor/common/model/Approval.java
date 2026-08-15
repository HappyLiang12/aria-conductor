package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approvals", indexes = {
        @Index(name = "idx_approvals_run", columnList = "runId"),
        @Index(name = "idx_approvals_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Approval {

    public enum ApprovalType { TOOL_CALL, SPEC_REVIEW }
    public enum ContentKind { MARKDOWN, HTML }

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID runId;

    private UUID toolCallId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    private String reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false, length = 20)
    private ApprovalType approvalType = ApprovalType.TOOL_CALL;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_kind", length = 20)
    private ContentKind contentKind;

    @Column(name = "knowledge_item_id", columnDefinition = "UUID")
    private UUID knowledgeItemId;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant decidedAt;

    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (requestedAt == null) requestedAt = Instant.now();
        if (status == null) status = ApprovalStatus.PENDING;
    }
}
