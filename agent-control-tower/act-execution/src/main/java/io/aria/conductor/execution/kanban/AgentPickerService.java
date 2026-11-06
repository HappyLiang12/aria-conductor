package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.common.exception.PickupRejectedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rule-based agent selection for kanban pickup (spec 4.2): template-id or label
 * containment match against the eligible pool, falling back to the first
 * eligible agent. No LLM call in v1.
 *
 * <p>The pool comes from {@link Candidates}, which derives both the pool and the
 * excluded reasons from {@link AgentPickupEligibility} — this class never
 * decides eligibility itself.
 */
@Service
public class AgentPickerService {

    /** @param role the agent's role (e.g. "ba" | "dev" | "qa"); may be null. */
    public record Candidate(UUID agentId, String name, String role) {}

    public record Choice(UUID agentId, String agentName) {}

    /** @param reasons every reason this agent was excluded; never empty. */
    public record Excluded(String name, List<AgentPickupEligibility.Reason> reasons) {}

    public interface Candidates {
        List<Candidate> eligible();

        List<Excluded> excluded();
    }

    private final Candidates candidates;

    public AgentPickerService(Candidates candidates) {
        this.candidates = candidates;
    }

    public Choice pick(String agentTemplateId, String title, String description) {
        List<Candidate> pool = candidates.eligible();
        if (pool.isEmpty()) {
            throw rejectEmptyPool();
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

    private PickupRejectedException rejectEmptyPool() {
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (Excluded e : candidates.excluded()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", e.name());
            entry.put("reasons", e.reasons().stream().map(Enum::name).toList());
            excluded.add(entry);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("evaluated", excluded.size());
        details.put("excluded", excluded);
        return new PickupRejectedException("NO_ELIGIBLE_AGENT",
                "No pickup-eligible agent: " + excluded.size() + " agent(s) evaluated and all excluded"
                        + (excluded.isEmpty() ? "" : " (" + summarize(excluded) + ")"),
                details);
    }

    private static String summarize(List<Map<String, Object>> excluded) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> e : excluded) {
            parts.add(e.get("name") + ": " + String.join("/", (List<String>) e.get("reasons")));
        }
        return String.join(", ", parts);
    }
}
