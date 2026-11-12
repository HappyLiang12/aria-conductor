package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.service.MemoryMentionExtractor.MentionSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lexical mention extraction for deterministic recall (spec §8.4 step 1): canonical-key
 * exact, alias exact, and case-insensitive token-boundary matching. No embeddings.
 */
class MemoryMentionExtractorTest {

    private final MemoryMentionExtractor extractor = new MemoryMentionExtractor();

    @Test
    void candidateKeysCoverTheCanonicalKeyPhrasingsOfAMention() {
        Set<String> keys = extractor.candidateKeys("Open the Aria Conductor repo", "D:/project/aria-conductor");

        assertThat(keys)
                .as("hyphenated, underscored and spaced phrasings all become exact lookup keys")
                .contains("aria", "conductor", "aria-conductor", "aria_conductor", "aria conductor");
    }

    /**
     * A goal long enough that its single tokens alone exceed the 200-key budget must still
     * yield the hyphenated canonical key of a phrase near its start: the exact key is only
     * reachable through the 2-gram, so the token pass may not consume the whole budget.
     */
    @Test
    void aGoalPastTheKeyBudgetStillCarriesItsHyphenatedCanonicalKey() {
        StringBuilder goal = new StringBuilder("aria-conductor ");
        for (int i = 0; i < 260; i++) {
            goal.append("filler").append(i).append(' ');
        }

        Set<String> keys = extractor.candidateKeys(goal.toString(), "");

        assertThat(keys)
                .as("a phrase candidate is never starved by the single-token pass")
                .contains("aria-conductor");
        assertThat(keys.size())
                .as("the candidate set stays bounded")
                .isLessThanOrEqualTo(MemoryMentionExtractor.MAX_CANDIDATE_KEYS);
    }

    @Test
    void canonicalKeyMatchingIsCaseInsensitiveAndTokenBounded() {
        MemoryEntity operator = entity("operator", "Operator", null);
        MemoryEntity prefix = entity("op", "OP", null);

        Map<UUID, MentionSource> mentions = extractor.match(
                "the Operator's timezone", null, List.of(operator, prefix));

        assertThat(mentions)
                .containsEntry(operator.getId(), MentionSource.CANONICAL_KEY)
                .as("'op' is a token prefix of 'Operator', not a mention")
                .doesNotContainKey(prefix.getId());
    }

    @Test
    void displayNameBeatsAliasAndAliasBeatsFocus() {
        MemoryEntity person = entity("operator-1", "Jane Doe", "{\"aliases\":[\"JD\",\"chief\"]}");

        assertThat(extractor.match("ask jane doe about it", null, List.of(person)))
                .containsEntry(person.getId(), MentionSource.DISPLAY_NAME);
        assertThat(extractor.match("ask jd about it", null, List.of(person)))
                .containsEntry(person.getId(), MentionSource.ALIAS);
        assertThat(extractor.match("status report", "operator-1", List.of(person)))
                .as("a focus entity is a match even when the text never names it")
                .containsEntry(person.getId(), MentionSource.FOCUS);
    }

    @Test
    void focusReferenceMatchesTheTrailingPathSegment() {
        MemoryEntity repo = entity("aria-conductor", "Aria Conductor", null);

        assertThat(extractor.match("", "D:/project/aria-conductor", List.of(repo)))
                .containsEntry(repo.getId(), MentionSource.FOCUS);
    }

    /**
     * T58 / F-O3. The stored display name is the DEFAULTED canonical key
     * (`MemoryCommandService:145`, `:209`), so the operator's name survives only as the tail of
     * `person:happy`. The tail is still the canonical key, not a fact value — recall never reads
     * fact values — so it ranks as {@link MentionSource#CANONICAL_KEY}.
     */
    @Test
    void aNamespacedCanonicalKeyIsReachableByItsTrailingSegment() {
        MemoryEntity person = entity("person:happy", "person:happy", null);

        assertThat(extractor.match("What do you remember about me, Happy?", null, List.of(person)))
                .as("the tail of person:happy names the entity in ordinary prose")
                .containsEntry(person.getId(), MentionSource.CANONICAL_KEY);
    }

    /**
     * T58 / F-B4. Both ACTIVE REPO keys from the live bar are unreachable by their own repository
     * name: the path-shaped key is never contained in the text, and the URL-shaped key additionally
     * carries the `.git` suffix. Both must resolve through the canonical key's tail.
     */
    @Test
    void aPathOrUrlCanonicalKeyIsReachableByItsRepositoryName() {
        MemoryEntity pathKey = entity("HappyLiang12/aria-conductor", "HappyLiang12/aria-conductor", null);
        MemoryEntity urlKey = entity("https://github.com/HappyLiang12/aria-conductor.git",
                "https://github.com/HappyLiang12/aria-conductor.git", null);

        Map<UUID, MentionSource> mentions = extractor.match(
                "aria-conductor repository release notes", null, List.of(pathKey, urlKey));

        assertThat(mentions)
                .containsEntry(pathKey.getId(), MentionSource.CANONICAL_KEY)
                .containsEntry(urlKey.getId(), MentionSource.CANONICAL_KEY);
    }

    /**
     * The tail is matched by the SAME boundary rule as the whole key, so widening reach must not
     * reopen the "op" vs "operator" hole one segment down.
     */
    @Test
    void aTrailingSegmentStillObeysTheTokenBoundary() {
        MemoryEntity person = entity("person:happy", "person:happy", null);

        assertThat(extractor.match("an unhappy customer", null, List.of(person)))
                .as("'happy' inside 'unhappy' is not a mention of person:happy")
                .isEmpty();
    }

    /** The ranking order is pinned by contract; the tail match reuses CANONICAL_KEY. */
    @Test
    void theMentionSourceRankingOrderIsPinned() {
        assertThat(MentionSource.values()).extracting(MentionSource::name)
                .containsExactly("CANONICAL_KEY", "DISPLAY_NAME", "ALIAS", "FOCUS", "NEIGHBOUR");
        assertThat(MentionSource.values()).extracting(MentionSource::rank)
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void anUnrelatedTextMatchesNothing() {
        MemoryEntity repo = entity("aria-conductor", "Aria Conductor", null);

        assertThat(extractor.match("deploy the frontend", null, List.of(repo))).isEmpty();
    }

    @Test
    void unparsableAttributesContributeNoAliasButTheDisplayNameStillMatches() {
        MemoryEntity person = entity("operator-1", "Jane Doe", "not json at all");

        assertThat(extractor.match("ask jane doe about it", null, List.of(person)))
                .containsEntry(person.getId(), MentionSource.DISPLAY_NAME);
        assertThat(extractor.match("ask jd about it", null, List.of(person))).isEmpty();
    }

    private static MemoryEntity entity(String canonicalKey, String displayName, String attributes) {
        return MemoryEntity.builder()
                .id(UUID.nameUUIDFromBytes(canonicalKey.getBytes()))
                .kind("PERSON")
                .canonicalKey(canonicalKey)
                .displayName(displayName)
                .attributes(attributes)
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .build();
    }
}
