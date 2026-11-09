package io.aria.conductor.execution.context;

import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextSection;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of budgeting one envelope: the sections that survived (with the
 * per-section caps applied), the history kept after trimming, and every drop
 * reason recorded along the way.
 */
record BudgetResult(
        List<ContextSection> sections,
        List<ContextMessage> history,
        List<String> dropped) {
}

/**
 * Fits one envelope into {@code maxChars} (spec §8.2 steps 5-6): per-section
 * caps first, then the history cap (oldest-first), then history trimmed to the
 * global cap, then the optional groups dropped in the fixed order
 * {@code workspace, skills, knowledge, memory}. Identity, policy and goal are
 * never dropped.
 *
 * <p>The budget counts the characters actually sent: a section costs its
 * rendered block and a history message costs its content.
 */
final class ContextBudget {

    static final List<String> DROP_ORDER = List.of("workspace", "skills", "knowledge", "memory");

    /** The last line of a section whose content was cut; a cap never splits a line. */
    static final String TRUNCATION_MARKER = "- ... (truncated)";

    private final ContextProperties properties;
    private final int maxChars;

    ContextBudget(ContextProperties properties, int maxChars) {
        this.properties = properties;
        this.maxChars = maxChars;
    }

    BudgetResult apply(List<ContextSection> sections,
                       List<ContextMessage> history,
                       List<String> dropped) {
        List<ContextSection> capped = new ArrayList<>();
        for (ContextSection section : sections) {
            int cap = properties.getCaps().getOrDefault(group(section.id()), Integer.MAX_VALUE);
            capped.add(section.estimatedChars() <= cap ? section : truncate(section, cap));
        }

        List<ContextMessage> kept = new ArrayList<>(history);
        int nextOriginalIndex = 0;
        int historyCap = properties.getCaps().getOrDefault("history", Integer.MAX_VALUE);
        while (!kept.isEmpty() && historyChars(kept) > historyCap) {
            kept.remove(0);
            dropped.add("history:dropped:" + nextOriginalIndex++);
        }

        int total = chars(capped, kept);
        while (total > maxChars && !kept.isEmpty()) {
            kept.remove(0);
            // Spec §6.6/§8.2 step 8: a drop is recorded by id and index, never by prompt text.
            dropped.add("history:dropped:" + nextOriginalIndex++);
            total = chars(capped, kept);
        }

        for (String group : DROP_ORDER) {
            if (total <= maxChars) {
                break;
            }
            List<ContextSection> matching = capped.stream()
                    .filter(section -> group(section.id()).equals(group))
                    .toList();
            capped.removeAll(matching);
            matching.forEach(section -> dropped.add(section.id() + ":budget"));
            total = chars(capped, kept);
        }

        return new BudgetResult(List.copyOf(capped), List.copyOf(kept), List.copyOf(dropped));
    }

    /** The section family: {@code knowledge:1} and {@code policy.agent} resolve to their group. */
    static String group(String id) {
        int colon = id.indexOf(':');
        int dot = id.indexOf('.');
        int end = colon < 0 ? dot : (dot < 0 ? colon : Math.min(colon, dot));
        return end < 0 ? id : id.substring(0, end);
    }

    /**
     * Cuts {@code section} to at most {@code cap} characters at a line boundary:
     * whole trailing lines are dropped and {@link #TRUNCATION_MARKER} records
     * that content was cut. When not even the marker fits, or no whole line fits
     * beside it, the body is dropped rather than cut mid-line.
     */
    private static ContextSection truncate(ContextSection section, int cap) {
        String content = section.content();
        if (content.length() <= cap) {
            return section;
        }
        String clipped = "";
        int room = cap - TRUNCATION_MARKER.length() - 1;
        if (room > 0) {
            String head = content.substring(0, Math.min(room, content.length()));
            int lineEnd = head.lastIndexOf('\n');
            String kept = lineEnd < 0 ? "" : head.substring(0, lineEnd + 1);
            clipped = kept + TRUNCATION_MARKER;
        }
        return new ContextSection(section.id(), section.title(), clipped, section.sensitivity(),
                section.sourceIds(), true, section.degradedReason());
    }

    static int chars(List<ContextSection> sections, List<ContextMessage> history) {
        return renderedChars(sections) + historyChars(history);
    }

    static int historyChars(List<ContextMessage> history) {
        return history.stream().mapToInt(message -> message.content().length()).sum();
    }

    static int renderedChars(List<ContextSection> sections) {
        return ContextAssembler.render(sections).length();
    }
}
