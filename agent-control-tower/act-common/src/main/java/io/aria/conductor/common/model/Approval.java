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
    public enum AskType { APPROVAL, QUESTION, REVIEW_REQUEST }
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

    /** HITL ask link: the kanban card this ask is surfaced on (nullable). */
    @Column(name = "kanban_item_id", length = 36)
    private String kanbanItemId;

    /** Fine-grained ask kind; approvalType stays the governance category. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "ask_type", nullable = false, length = 20)
    private AskType askType = AskType.APPROVAL;

    @Column(name = "context_md", columnDefinition = "TEXT")
    private String contextMd;

    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "knowledge_item_id", columnDefinition = "UUID")
    private UUID knowledgeItemId;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant decidedAt;

    private Instant expiresAt;

    /**
     * Provenance (V60): legacy tool-call gate vs. ACP permission ask. Defaults to
     * {@code LEGACY_GATE} so every pre-existing code path keeps its previous behavior; an ACP
     * ask additionally has a companion {@link AcpPermissionRequest} row keyed by this id.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private ApprovalSource source = ApprovalSource.LEGACY_GATE;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (requestedAt == null) requestedAt = Instant.now();
        if (status == null) status = ApprovalStatus.PENDING;
    }
}
