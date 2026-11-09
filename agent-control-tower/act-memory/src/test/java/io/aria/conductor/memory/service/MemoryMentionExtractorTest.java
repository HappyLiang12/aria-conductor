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
