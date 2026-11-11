package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Sensitivity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * A non-Aria agent's managed policy (spec §8.1): the configured
 * {@code systemPrompt}, or the agent's role when none is configured. Aria's
 * policy is the platform assistant's own (act-aria), so this contributor does
 * not apply to Aria runs.
 *
 * <p>Optional: an agent row with neither prompt nor role degrades to no policy
 * section rather than failing the run.
 */
@Component
public class AgentPolicyContextContributor implements ContextContributor {

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() { };

    private final AgentRepository agents;

    public AgentPolicyContextContributor(AgentRepository agents) {
        this.agents = agents;
    }

    @Override
    public String id() {
        return "policy.agent";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return request.agentId() != null && !AriaConstants.ARIA_AGENT_ID.equals(request.agentId());
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        Agent agent = agents.findById(request.agentId()).orElse(null);
        if (agent == null) {
            return List.of();
        }
        String content = configuredPrompt(agent);
        if (content == null && agent.getRole() != null && !agent.getRole().isBlank()) {
            content = agent.getRole();
        }
        if (content == null) {
            return List.of();
        }
        return List.of(new ContextSection(id(), "Policy", content,
                Sensitivity.INTERNAL, List.of(agent.getId().toString()), false, null));
    }

    /** The {@code systemPrompt} entry of the agent's JSON config, or null when absent. */
    static String configuredPrompt(Agent agent) {
        String config = agent.getConfig();
        if (config == null || config.isBlank()) {
            return null;
        }
        try {
            Object prompt = CONFIG_MAPPER.readValue(config, CONFIG_TYPE).get("systemPrompt");
            if (prompt instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (Exception ignored) {
            // A malformed config is not a prompt; fall through to the role.
        }
        return null;
    }
}
