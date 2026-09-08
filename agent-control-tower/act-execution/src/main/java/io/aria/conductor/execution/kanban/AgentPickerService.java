package io.aria.conductor.execution.kanban;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rule-based agent selection for kanban pickup (spec 4.2): template-id or
 * label containment match against healthy agents, falling back to the first
 * healthy agent. No LLM call in v1.
 */
@Service
public class AgentPickerService {

    public record Candidate(UUID agentId, String name) {}

    public record Choice(UUID agentId, String agentName) {}

    public interface Candidates {
        List<Candidate> healthy();
    }

    private final Candidates candidates;

    public AgentPickerService(Candidates candidates) {
        this.candidates = candidates;
    }

    public Choice pick(String agentTemplateId, String title, String description) {
        List<Candidate> pool = candidates.healthy();
        if (pool.isEmpty()) {
            throw new IllegalStateException("No healthy agent available for kanban pickup");
        }
        Optional<Candidate> matched = Optional.empty();
        if (agentTemplateId != null && !agentTemplateId.isBlank()) {
            String needle = agentTemplateId.toLowerCase();
            matched = pool.stream()
                    .filter(c -> c.name() != null && c.name().toLowerCase().contains(needle))
                    .findFirst();
        }
        Candidate chosen = matched.orElse(pool.get(0));
        return new Choice(chosen.agentId(), chosen.name());
    }
}
