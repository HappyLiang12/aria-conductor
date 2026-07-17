package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "skill_tools")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillTool {
    @EmbeddedId
    private SkillToolId id;
}
