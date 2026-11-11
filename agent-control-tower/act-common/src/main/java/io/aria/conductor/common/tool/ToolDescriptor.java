package io.aria.conductor.common.tool;

import java.util.Objects;

/**
 * One catalog entry of the tool surface (spec P7): the reviewed policy and the
 * auto-approve flag travel with the tool's name and one-line description, so
 * the policy registry, the inventory, the auto-approve validation and the
 * generated tool prompt section all read the same classification.
 */
public record ToolDescriptor(
        String name,
        String description,
        ToolPolicy policy,
        boolean autoApproveRead) {

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(policy, "policy");
        description = description == null ? "" : description;
    }
}
