package io.aria.conductor.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.memory.model.MemoryEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lexical entity-mention extraction for deterministic recall (spec §8.4 step 1). It is
 * deliberately an exact matcher — canonical key, display name, and aliases compared
 * case-insensitively on token boundaries — so the same text always yields the same
 * mentions. No embeddings, no fuzzy scoring.
 * <p>
 * {@link #candidateKeys(String, String)} produces the exact keys handed to
 * {@code MemoryEntityRepository.findByCanonicalKeyInAndStatus}; {@link #match} narrows a
 * candidate set (the ACTIVE entities the repository could not query by alias) to the
 * entities the text actually names, with the strongest mention source per id.
 */
@Component
public class MemoryMentionExtractor {

    /** The goal plus three user turns is bounded before tokenising, so recall stays cheap. */
    static final int MAX_TEXT_CHARS = 4_000;

    /** Upper bound of exact keys sent to one {@code IN} query. */
    static final int MAX_CANDIDATE_KEYS = 200;

    /**
     * Keys the single-token pass may not spend, so the 2-3 gram phrasings keep a share of
     * the budget. A hyphenated canonical key ("aria-conductor") is reachable only through
     * its 2-gram, so a long goal must not fill all 200 slots with single words first.
     */
    static final int PHRASE_RESERVE = 80;

    /** Longest phrase joined into one canonical-key candidate ("aria conductor repo"). */
    static final int MAX_NGRAM = 3;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern PATH_SEPARATOR = Pattern.compile("[\\\\/]");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Upper bound of compiled boundary patterns kept for reuse; bounded so it cannot grow. */
    private static final int MAX_CACHED_PATTERNS = 2_048;

    private static final Map<String, Pattern> BOUNDARY_PATTERNS = new ConcurrentHashMap<>();

    /**
     * Exact canonical-key candidates: every token and every adjacent 2-3 token phrase of
     * the text and of the focus reference, joined by {@code -}, {@code _}, and space so
     * the three usual canonical-key spellings are all looked up in one query.
     */
    public Set<String> candidateKeys(String text, String focusRef) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        // The focus reference is what the turn is explicitly about, so it is looked up
        // before the text: a long goal must not push it out of the bounded budget.
        String focus = normalize(focusRef);
        if (!focus.isEmpty()) {
            addCandidate(keys, focus);
            addCandidate(keys, trailingSegment(focus));
            addPhrases(keys, focus);
        }
        addPhrases(keys, normalize(text));
        return keys;
    }

    /**
     * The strongest lexical mention per entity id: canonical key beats display name beats
     * alias beats a focus reference. Entities the text never names are absent.
     */
    public Map<UUID, MentionSource> match(String text, String focusRef, Collection<MemoryEntity> candidates) {
        String haystack = normalize(text);
        String focus = normalize(focusRef);
        Map<UUID, MentionSource> mentions = new TreeMap<>();
        if (candidates == null) {
            return mentions;
        }
        for (MemoryEntity entity : candidates) {
            if (entity == null || entity.getId() == null) {
                continue;
            }
            MentionSource source = sourceOf(entity, haystack, focus);
            if (source != null) {
                mentions.put(entity.getId(), source);
            }
        }
        return mentions;
    }

    private static MentionSource sourceOf(MemoryEntity entity, String haystack, String focus) {
        if (mentioned(haystack, entity.getCanonicalKey())) {
            return MentionSource.CANONICAL_KEY;
        }
        if (mentioned(haystack, entity.getDisplayName())) {
            return MentionSource.DISPLAY_NAME;
        }
        if (aliasesOf(entity).stream().anyMatch(alias -> mentioned(haystack, alias))) {
            return MentionSource.ALIAS;
        }
        if (focusMatches(entity.getCanonicalKey(), focus)) {
            return MentionSource.FOCUS;
        }
        return null;
    }

    private static boolean focusMatches(String canonicalKey, String focus) {
        String key = normalize(canonicalKey);
        if (focus.isEmpty() || key.isEmpty()) {
            return false;
        }
        return focus.equals(key) || trailingSegment(focus).equals(key);
    }

    /** Case-insensitive containment that does not match inside a word ("op" vs "operator"). */
    private static boolean mentioned(String haystack, String needle) {
        String normalized = normalize(needle);
        if (haystack.isEmpty() || normalized.isEmpty() || !haystack.contains(normalized)) {
            return false;
        }
        return boundaryPattern(normalized).matcher(haystack).find();
    }

    /**
     * The token-boundary pattern for one normalized needle, cached: recall matches every
     * candidate entity's canonical key, display name, and aliases on every turn, and the
     * same few needles repeat, so compiling per comparison would dominate the 200 ms
     * budget. The cache is capped; past the cap it stops inserting and compiles instead.
     */
    private static Pattern boundaryPattern(String normalized) {
        Pattern cached = BOUNDARY_PATTERNS.get(normalized);
        if (cached != null) {
            return cached;
        }
        Pattern compiled = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalized) + "(?![a-z0-9])");
        if (BOUNDARY_PATTERNS.size() < MAX_CACHED_PATTERNS) {
            BOUNDARY_PATTERNS.putIfAbsent(normalized, compiled);
        }
        return compiled;
    }

    private static void addPhrases(Set<String> keys, String text) {
        if (text.isEmpty()) {
            return;
        }
        List<String> tokens = new ArrayList<>();
        TOKEN.matcher(text).results().forEach(match -> tokens.add(match.group()));
        int tokenLimit = MAX_CANDIDATE_KEYS - PHRASE_RESERVE;
        for (String token : tokens) {
            if (keys.size() >= tokenLimit) {
                break;
            }
            addCandidate(keys, token);
        }
        for (int size = 2; size <= MAX_NGRAM; size++) {
            for (int start = 0; start + size <= tokens.size(); start++) {
                String phrase = String.join(" ", tokens.subList(start, start + size));
                // Hyphenated first: that is the canonical-key spelling of a multi-word
                // name, and the phrase share of the budget may not reach every spelling.
                addCandidate(keys, phrase.replace(' ', '-'));
                addCandidate(keys, phrase);
                addCandidate(keys, phrase.replace(' ', '_'));
            }
        }
    }

    private static void addCandidate(Set<String> keys, String key) {
        if (keys.size() >= MAX_CANDIDATE_KEYS || key == null || key.isEmpty()) {
            return;
        }
        keys.add(key);
    }

    private static String trailingSegment(String normalizedPath) {
        String[] segments = PATH_SEPARATOR.split(normalizedPath);
        return segments.length == 0 ? "" : segments[segments.length - 1];
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.length() > MAX_TEXT_CHARS ? raw.substring(0, MAX_TEXT_CHARS) : raw;
        return text.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code attributes} is display metadata JSON; a malformed value simply carries no alias. */
    private static List<String> aliasesOf(MemoryEntity entity) {
        String attributes = entity.getAttributes();
        if (attributes == null || attributes.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(attributes);
            List<String> aliases = new ArrayList<>();
            JsonNode list = root.get("aliases");
            if (list != null && list.isArray()) {
                list.forEach(alias -> {
                    if (alias.isTextual()) {
                        aliases.add(alias.asText());
                    }
                });
            } else if (list != null && list.isTextual()) {
                aliases.add(list.asText());
            }
            JsonNode single = root.get("alias");
            if (single != null && single.isTextual()) {
                aliases.add(single.asText());
            }
            return aliases;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Which lexical signal named an entity; lower rank sorts first during recall. */
    public enum MentionSource {

        CANONICAL_KEY(0),
        DISPLAY_NAME(1),
        ALIAS(2),
        FOCUS(3),
        NEIGHBOUR(4);

        private final int rank;

        MentionSource(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }
}
