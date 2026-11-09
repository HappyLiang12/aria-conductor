package io.aria.conductor.common.context;

import io.aria.conductor.common.model.Sensitivity;

import java.util.List;
import java.util.Objects;

/**
 * One typed block of the context envelope (spec §8.1): provenance ids,
 * sensitivity, and a size estimate the assembler budgets with. A missing
 * sensitivity fails closed to {@link Sensitivity#INTERNAL}; the estimate is
 * derived from the content, never supplied by the contributor.
 */
public record ContextSection(
        String id,
        String title,
        String content,
        Sensitivity sensitivity,
        List<String> sourceIds,
        boolean truncated,
        String degradedReason) {

    public ContextSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        content = content == null ? "" : content;
        sensitivity = sensitivity == null ? Sensitivity.INTERNAL : sensitivity;
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }

    public int estimatedChars() {
        return content.length();
    }

    public int estimatedTokens() {
        return (int) Math.ceil(content.length() / 3.5);
    }
}
