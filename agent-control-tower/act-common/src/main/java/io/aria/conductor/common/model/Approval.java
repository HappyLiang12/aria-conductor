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
