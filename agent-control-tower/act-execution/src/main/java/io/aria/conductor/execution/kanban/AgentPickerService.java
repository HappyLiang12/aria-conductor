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

    /** @param role the agent's role (e.g. "ba" | "dev" | "qa"); may be null. */
    public record Candidate(UUID agentId, String name, String role) {}

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
            // Most explicit match wins: an exact name pin (operator intent), then
            // role equality — the new-task modal sends template ids ("ba"/"dev"/
            // "qa") that are role keys, not name fragments ("Business Analyst
            // Agent" contains no "ba") — then the legacy containment match.
            matched = pool.stream()
                    .filter(c -> c.name() != null && c.name().toLowerCase().equals(needle))
                    .findFirst();
            if (matched.isEmpty()) {
                matched = pool.stream()
                        .filter(c -> c.role() != null && c.role().toLowerCase().equals(needle))
                        .findFirst();
            }
            if (matched.isEmpty()) {
                matched = pool.stream()
                        .filter(c -> c.name() != null && c.name().toLowerCase().contains(needle))
                        .findFirst();
            }
        }
        Candidate chosen = matched.orElse(pool.get(0));
        return new Choice(chosen.agentId(), chosen.name());
    }
}
