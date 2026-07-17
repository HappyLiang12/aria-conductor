package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "agent_skills")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkill {

    @EmbeddedId
    private AgentSkillId id;
}
