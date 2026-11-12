package io.aria.conductor.memory.service;

import java.util.Locale;
import java.util.Set;

/**
 * The one entity-kind vocabulary and casing rule for both halves of memory.
 * <p>
 * {@link io.aria.conductor.memory.model.MemoryEntity} declares the open vocabulary
 * {@code PERSON, PROJECT, REPO, MACHINE, AGENT, TASK, ORG} and stores the uppercase name, while
 * every caller-facing surface (the Aria prompt, the MCP tool parameters, the REST body) teaches
 * the lowercase spelling ({@code person / person:happy}). The kind is half of the row identity —
 * {@code UNIQUE (kind, canonical_key)} — so the two spellings must be reconciled before either a
 * filter or an identity probe reaches the repository; otherwise the same person owns two rows.
 * <p>
 * T53 (live bar) established this rule on the read path as a private
 * {@code MemoryQueryService.normalizeKind}: trim, uppercase with {@link Locale#ROOT}, accept only a
 * known kind, and refuse anything else with a named {@link MemoryValidationException} so an
 * unknown kind can never be read as "nothing exists". This class is that same rule, extracted so
 * the write path ({@link MemoryCommandService#createEntity}) enforces it too instead of storing
 * the kind as given. The vocabulary and the casing transform are identical to T53's; the only
 * difference is the trailing remedy sentence, which is caller-specific and therefore left to each
 * surface.
 * <p>
 * Post-merge composition with T53: {@code MemoryQueryService}'s private copy is behaviourally the
 * same rule, and can be replaced with a one-line delegation to {@link #normalize(String)} without
 * changing any read behaviour or assertion.
 */
public final class MemoryEntityKind {

    /**
     * The documented entity-kind vocabulary ({@link io.aria.conductor.memory.model.MemoryEntity}:
     * "Open vocabulary: PERSON, PROJECT, REPO, MACHINE, AGENT, TASK, ORG"). The stored form is the
     * uppercase name; callers may send any casing of one of these.
     */
    public static final Set<String> KNOWN_KINDS = Set.of(
            "PERSON", "PROJECT", "REPO", "MACHINE", "AGENT", "TASK", "ORG");

    private MemoryEntityKind() {
    }

    /**
     * Canonicalises a caller-supplied kind to the stored vocabulary.
     *
     * @param kind the caller's kind, any casing
     * @return the uppercase stored form, or {@code null} when the caller supplied no kind
     *         (meaning "no filter" on a read, and impossible on a write because the field is
     *         required there)
     * @throws MemoryValidationException when the kind is outside {@link #KNOWN_KINDS}; it is
     *         refused, never silently coerced, because a coerced unknown kind would create a row
     *         no filtered read can find
     */
    public static String normalize(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        String trimmed = kind.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!KNOWN_KINDS.contains(normalized)) {
            throw new MemoryValidationException("Unknown memory entity kind '" + trimmed
                    + "'. Entity kinds are case-insensitive: the accepted kinds are "
                    + String.join(", ", KNOWN_KINDS.stream().sorted().toList()) + ".");
        }
        return normalized;
    }
}
