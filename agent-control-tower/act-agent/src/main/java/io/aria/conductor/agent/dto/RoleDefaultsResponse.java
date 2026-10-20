package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.SkillContext;

import java.util.List;

/**
 * Recommended default skills for a role (rule-based role templates).
 * Consumed by the dashboard to pre-check recommendations at agent creation
 * and via the "Apply role defaults" action.
 */
public record RoleDefaultsResponse(List<SkillContext> skills) {
}
