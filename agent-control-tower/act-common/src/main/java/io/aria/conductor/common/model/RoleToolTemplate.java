package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_tool_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleToolTemplate {
    @EmbeddedId
    private RoleToolTemplateId id;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;
}
