package io.aria.conductor.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillToolId implements Serializable {
    @Column(name = "skill_id", length = 36)
    private String skillId;

    @Column(name = "tool_id", length = 36)
    private String toolId;
}
