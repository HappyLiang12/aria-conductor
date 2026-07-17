package io.aria.conductor.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolId implements Serializable {
    @Column(name = "agent_id", length = 36)
    private String agentId;

    @Column(name = "tool_id", length = 36)
    private String toolId;
}
