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

    /**
     * T61 / re-review m1. A canonical key that merely ENDS in a separator has no tail: the segment
     * before it is the namespace, not a name. `Pattern.split` drops the empty field after a trailing
     * separator, so splitting alone reported "person" and the extractor named the entity from the
     * ordinary English word in any text — at canonical-key rank. `person:` and `agent:` are storable
     * (`MemoryValueGuard.checkKey` rejects only credential-shaped keys) and carry the defaulted
     * display name, so only the tail could have named them.
     */
    @Test
    void aKeyThatEndsInASeparatorHasNoTail() {
        MemoryEntity personNamespace = entity("person:", "person:", null);
        MemoryEntity agentNamespace = entity("agent:", "agent:", null);

        assertThat(extractor.match("who is the person here", null, List.of(personNamespace)))
                .as("'person' before the trailing separator is the namespace, not the entity's name")
                .isEmpty();
        assertThat(extractor.match("which agent handles this", null, List.of(agentNamespace)))
                .as("the same false mention for a namespace-only agent key")
                .isEmpty();
    }

    /**
     * The shape rule itself, pinned next to its positive controls: a tail exists only for a key with
     * a separator that has a non-empty segment after it.
     */
    @Test
    void onlyAShapedKeyHasATail() {
        assertThat(MemoryMentionExtractor.trailingSegment("person:happy")).isEqualTo("happy");
        assertThat(MemoryMentionExtractor.trailingSegment("HappyLiang12/aria-conductor"))
                .isEqualTo("aria-conductor");
        assertThat(MemoryMentionExtractor.trailingSegment(
                "https://github.com/HappyLiang12/aria-conductor.git"))
                .as("the URL shape still yields the repository name")
                .isEqualTo("aria-conductor");
        assertThat(MemoryMentionExtractor.trailingSegment("person"))
                .as("no separator: the whole key is already the whole-key comparison")
                .isEmpty();
        assertThat(MemoryMentionExtractor.trailingSegment("person:"))
                .as("a trailing separator has no non-empty segment after it")
                .isEmpty();
        assertThat(MemoryMentionExtractor.trailingSegment("agent:")).isEmpty();
        assertThat(MemoryMentionExtractor.trailingSegment("")).isEmpty();
        // P3a (fix-round-6b re-review): a PATH-shaped key stored with a trailing separator loses its
        // tail too, so its parent directory never names it. The loss is deliberate — the shape rule
        // reads "the segment after the last separator, if any" and the last field here is empty — and
        // this row exists so it cannot be narrowed or widened without a failing test.
        assertThat(MemoryMentionExtractor.trailingSegment("d:/project/"))
                .as("a copy-pasted directory with a trailing separator has no segment after it")
                .isEmpty();
        assertThat(MemoryMentionExtractor.trailingSegment("a/b/"))
                .as("the same shape in a relative path")
                .isEmpty();
        assertThat(MemoryMentionExtractor.trailingSegment("/"))
                .as("a key that is only a separator has no segment after it")
                .isEmpty();
        assertThat(MemoryMentionExtractor.trailingSegment(":"))
                .as("the namespace separator alone is the same shape")
                .isEmpty();
    }

    /**
     * P3a (fix-round-6b re-review) — DISCLOSED RESIDUAL, pinned as known and unchanged.
     * <p>
     * A key stored with a trailing separator (`D:/project/`) keeps the whole string as its defaulted
     * display name (`MemoryCommandService:145`), and the whole-key comparison needs the literal
     * trailing separator, which ordinary prose never contains. With no tail and no other name, nothing
     * names it from prose: recall silently loses a REPO/PROJECT/MACHINE entity stored from a
     * copy-pasted path. Accepted by controller ruling for this round; do not "fix" this pin, and do
     * not implement a tail rule here.
     */
    @Test
    void aTrailingSeparatorKeyCannotNameAnEntityFromProse() {
        MemoryEntity directory = entity("D:/project/", "D:/project/", null);
        MemoryEntity inside = entity("D:/project/aria-conductor", "D:/project/aria-conductor", null);

        assertThat(extractor.match("open the project folder", null, List.of(directory)))
                .as("no tail, and the whole key is not in prose, so the entity is not named")
                .isEmpty();
        assertThat(extractor.match("open the aria-conductor repository", null, List.of(inside)))
                .as("positive control: the same path shape without the trailing separator keeps its tail")
                .containsEntry(inside.getId(), MentionSource.CANONICAL_KEY);
    }

    /**
     * P3c (fix-round-6b re-review) — DISCLOSED RESIDUAL, pinned as known and unchanged, NOT as
     * desirable.
     * <p>
     * The focus reference is looked up as an exact key AND fed to the token pass
     * ({@code addPhrases}, the single-token loop at {@code MemoryMentionExtractor:186-192}), so a focus
     * of {@code person:} still contributes the bare token {@code person}. Recall admits any exact-key
     * hit from {@code findByCanonicalKeyInAndStatus} at {@link MentionSource#CANONICAL_KEY} rank 0 with
     * no text check ({@code MemoryRecallService:90-94}), so an entity whose canonical key is literally
     * {@code person} stays reachable from this focus even though the guarded tail clause no longer mints
     * a FOCUS match for it (pinned above by {@link #aGuardedFocusTailMintsNoFocusMatch()}).
     * <p>
     * This is the pre-existing token pass every word of the text already feeds, so it adds no new class
     * of reach; the seam a future fix would change is the token pass, not the tail guard. If this test
     * fails, that seam changed — update the residual record deliberately.
     */
    @Test
    void aNamespaceOnlyFocusStillFeedsTheBareTokenToTheCandidateSet() {
        Set<String> keys = extractor.candidateKeys("", "person:");

        assertThat(keys)
                .as("the focus key itself, plus the bare token the single-token pass derives from it")
                .containsExactlyInAnyOrder("person:", "person");
    }

    /**
     * T61 / brief item 3. The focus reference reaches the exact-key `IN` query and the focus match
     * through the SAME tail definition, so the shape guard governs it too: a focus of `person:`
     * yields no tail and therefore cannot stand in for an entity keyed `person` through the tail
     * clause. A genuinely shaped focus still names its tail (positive control).
     */
    @Test
    void aGuardedFocusTailMintsNoFocusMatch() {
        MemoryEntity bare = entity("person", "Person", null);
        MemoryEntity person = entity("person:happy", "person:happy", null);

        assertThat(extractor.match("", "person:", List.of(bare)))
                .as("the namespace-only focus contributes no tail, so no focus match")
                .isEmpty();
        assertThat(extractor.match("", "person:happy", List.of(person)))
                .as("a shaped focus still reaches its entity by the tail")
                .containsEntry(person.getId(), MentionSource.FOCUS);
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
