package io.aria.conductor.execution.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context envelope budgets ({@code aria.context.*}, spec §8.2 step 5): a global
 * character cap and one cap per section group. The group of a section is the
 * part of its id before {@code :} or {@code .} — {@code knowledge:1} and
 * {@code policy.agent} both resolve to their family's cap.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.context")
public class ContextProperties {

    /** Characters of the whole envelope (system blocks plus history). */
    private int maxChars = 24_000;

    /** Per-section-group character cap. A group without an entry is uncapped. */
    private Map<String, Integer> caps = new LinkedHashMap<>(Map.of(
            "identity", 2_000, "policy", 6_000, "goal", 4_000,
            "memory", 4_000, "knowledge", 4_000, "skills", 3_000,
            "state", 2_000, "workspace", 2_000, "tools", 3_000, "history", 8_000));
}
