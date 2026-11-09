package io.aria.conductor.execution.context;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds one prompt envelope (spec §8.2): every applicable contributor in
 * canonical order, the provider ceiling enforced twice (maxSensitivity passed
 * to the contributors, then a double-check on each declared section), the
 * budget applied, and the sections rendered as {@code ## <title>} blocks.
 * History is never part of the system text.
 *
 * <p>A required contributor that fails or produces nothing fails the assembly
 * with {@link ContextAssemblyException}; an optional one only degrades its own
 * section. Every drop and degradation is reported so the manifest records it.
 */
@Component
public class ContextAssembler {

    private final List<ContextContributor> contributors;
    private final ProviderCeilingResolver ceilings;
    private final ContextProperties properties;

    public ContextAssembler(List<ContextContributor> contributors,
                            ProviderCeilingResolver ceilings,
                            ContextProperties properties) {
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
        this.ceilings = ceilings;
        this.properties = properties;
    }

    public AssembledContext assemble(ContextRequest request, List<ContextMessage> history) {
        Sensitivity ceiling = min(request.maxSensitivity(), ceilings.ceilingFor(request.providerId()));
        ContextRequest bounded = request.withMaxSensitivity(ceiling);
        List<ContextSection> sections = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> degraded = new ArrayList<>();

        for (ContextContributor contributor : ordered()) {
            if (!contributor.appliesTo(bounded)) {
                continue;
            }
            List<ContextSection> produced;
            try {
                produced = contributor.contribute(bounded);
            } catch (RuntimeException e) {
                if (contributor.required()) {
                    throw new ContextAssemblyException(contributor.id(), e);
                }
                degraded.add(contributor.id() + ":error");
                continue;
            }
            if ((produced == null || produced.isEmpty()) && contributor.required()) {
                throw new ContextAssemblyException(contributor.id(), null);
            }
            if (produced == null) {
                continue;
            }
            for (ContextSection section : produced) {
                if (section.sensitivity().ordinal() > ceiling.ordinal()) {
                    dropped.add(section.id() + ":sensitivity");
                } else {
                    sections.add(section);
                }
            }
        }

        BudgetResult budget = new ContextBudget(properties, request.maxChars())
                .apply(sections, history == null ? List.of() : history, dropped);
        String text = render(budget.sections());
        return new AssembledContext(text, budget.history(), estimate(text, budget.history()),
                budget.sections(), budget.dropped(), List.copyOf(degraded));
    }

    private List<ContextContributor> ordered() {
        return contributors.stream()
                .sorted(Comparator.comparingInt(ContextContributor::order))
                .toList();
    }

    /**
     * Renders one block per section: {@code "## <title>\n<content>\n\n"}. The
     * budget measures this exact text, so there is one format definition.
     */
    static String render(List<ContextSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (ContextSection section : sections) {
            sb.append("## ").append(section.title()).append('\n')
                    .append(section.content()).append("\n\n");
        }
        return sb.toString();
    }

    /** Token estimate of what is sent: {@code ceil(chars / 3.5)} (spec §8.2). */
    private static int estimate(String text, List<ContextMessage> history) {
        int chars = text.length()
                + history.stream().mapToInt(message -> message.content().length()).sum();
        return (int) Math.ceil(chars / 3.5);
    }

    private static Sensitivity min(Sensitivity left, Sensitivity right) {
        return left.ordinal() <= right.ordinal() ? left : right;
    }
}
