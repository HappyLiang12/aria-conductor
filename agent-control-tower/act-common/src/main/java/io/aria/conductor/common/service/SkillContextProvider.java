package io.aria.conductor.common.service;

import io.aria.conductor.common.model.SkillContext;
import java.util.List;

/**
 * Cycle-safe seam: act-execution depends on this interface (act-common);
 * act-knowledge provides the implementation that reads SkillDefinition.
 */
public interface SkillContextProvider {
    /**
     * Enabled, SKILL-stage skills assigned to the given agent, projected to
     * prompt-injectable DTOs. Returns an empty list if the agent has no skills.
     */
    List<SkillContext> getEnabledSkillsForAgent(String agentId);
}
