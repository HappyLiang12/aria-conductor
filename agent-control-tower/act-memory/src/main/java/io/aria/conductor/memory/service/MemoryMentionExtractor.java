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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical entity-mention extraction for deterministic recall (spec §8.4 step 1). It is
 * deliberately an exact matcher — canonical key (or the key's trailing path/URL/namespace
 * segment), display name, and aliases compared case-insensitively on token boundaries — so the
 * same text always yields the same mentions. No embeddings, no fuzzy scoring.
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
    /**
     * Path, URL and namespace separators. {@code :} is a separator too, so a namespaced canonical
     * key ("person:happy", "agent:&lt;uuid&gt;") has the same tail semantics as a path or a URL.
     */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("[\\\\/:]");
    /** A repository URL's suffix; the matching canonical key usually omits it. */
    private static final String GIT_SUFFIX = ".git";
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
        // A path-, URL- or namespace-shaped key ("HappyLiang12/aria-conductor",
        // "https://…/aria-conductor.git", "person:happy") is never contained in ordinary prose, so
        // its own tail is the handle an operator actually types. The tail IS the canonical key, not
        // a separate signal, so it carries the canonical-key rank instead of a new enum constant
        // (the ranking order is pinned). Matching it by the same boundary rule keeps the "op" vs
        // "operator" guarantee one segment down.
        if (mentionedTail(haystack, entity.getCanonicalKey())) {
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

    /**
     * True when the text names the key's trailing segment. A key with no tail — no separator, or a
     * separator at the very end ("person:") — has no name after it and is skipped; such a key is
     * already covered by the whole-key comparison, so this can only widen reach for shaped keys.
     */
    private static boolean mentionedTail(String haystack, String canonicalKey) {
        String key = normalize(canonicalKey);
        String tail = trailingSegment(key);
        return !tail.isEmpty() && !tail.equals(key) && mentioned(haystack, tail);
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

    /**
     * The key's tail: the last path, URL or namespace segment, and only when the key has that shape —
     * a separator with a non-empty segment after it. {@code :} separates a namespaced key
     * ("person:happy") exactly as {@code /} separates a path, and a repository URL ends in
     * {@code .git} where its canonical key does not, so that suffix is dropped — both shapes then
     * yield the name an operator types. Used by the mention match, by the focus match and by the
     * exact-key candidate set, so one definition of "tail" governs all three.
     * <p>
     * A key with no separator, and a key that ends in a separator ("person:", "agent:"), have no
     * tail. The shape of the key decides, not the word: {@code Pattern.split} drops the empty field
     * after a trailing separator, so splitting alone would report the segment <em>before</em> it —
     * the namespace, not a name — and the extractor would then name the entity from the ordinary
     * English word, at canonical-key rank.
     */
    static String trailingSegment(String normalizedPath) {
        Matcher separator = PATH_SEPARATOR.matcher(normalizedPath);
        int last = -1;
        while (separator.find()) {
            last = separator.start();
        }
        if (last < 0) {
            return "";
        }
        String tail = normalizedPath.substring(last + 1);
        // A trailing separator leaves no segment after it: that empty field is a namespace boundary,
        // and the field Pattern.split would report for it is the prefix on the other side.
        if (tail.isEmpty()) {
            return "";
        }
        if (tail.length() > GIT_SUFFIX.length() && tail.endsWith(GIT_SUFFIX)) {
            return tail.substring(0, tail.length() - GIT_SUFFIX.length());
        }
        return tail;
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
