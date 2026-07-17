package io.aria.conductor.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleToolTemplateId implements Serializable {
    @Column(length = 50)
    private String role;

    @Column(name = "tool_id", length = 36)
    private String toolId;
}
