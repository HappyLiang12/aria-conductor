package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "agent_tools")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTool {

    @EmbeddedId
    private AgentToolId id;

    @Column(name = "assigned_by", nullable = false, length = 50)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @PrePersist
    void onCreate() {
        if (assignedAt == null) assignedAt = Instant.now();
    }
}
