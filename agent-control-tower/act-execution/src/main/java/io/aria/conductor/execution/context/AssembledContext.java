package io.aria.conductor.execution.context;

import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextSection;

import java.util.List;

/**
 * One assembled envelope: the rendered system text, the bounded history kept
 * after budgeting, the token estimate, the sections that survived, and every
 * drop or degradation reason the manifest records (spec §8.2 step 8).
 */
public record AssembledContext(
        String systemText,
        List<ContextMessage> history,
        int estimatedTokens,
        List<ContextSection> sections,
        List<String> dropped,
        List<String> degraded) {
}
