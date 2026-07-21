package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_skill_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleSkillTemplate {
    @EmbeddedId
    private RoleSkillTemplateId id;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;
}
