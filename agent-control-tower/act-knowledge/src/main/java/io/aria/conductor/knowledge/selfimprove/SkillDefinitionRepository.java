package io.aria.conductor.knowledge.selfimprove;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, String> {

    /**
     * Atomically flips {@code enabled} in a single UPDATE statement.
     * <p>
     * The previous controller-side read-modify-write
     * ({@code findById → setEnabled(!enabled) → save}) lost updates when two
     * requests read the same initial state, so N concurrent toggles could
     * perform fewer than N flips. E2E {@code skill-lifecycle} test D asserts
     * 10 concurrent toggles converge back to the initial state — a premise
     * that only holds if every toggle flips exactly once (CI failed on it
     * twice consecutively on PR #77, run 33965452850 + rerun). The row-level
     * lock taken by the UPDATE (H2 and MariaDB) serializes concurrent
     * togglers, making the final state {@code initial XOR (N mod 2)}.
     * <p>
     * Bulk JPQL bypasses {@code @PreUpdate}, so {@code updatedAt} is refreshed
     * explicitly to preserve the old save-path contract. Use
     * {@code clearAutomatically} so a subsequent {@code findById} within the
     * same request re-reads the flipped row instead of stale first-level
     * cache state.
     *
     * @return number of rows updated (0 → no skill with the given id)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SkillDefinition s SET s.enabled = CASE s.enabled WHEN true THEN false ELSE true END, "
            + "s.updatedAt = :now WHERE s.id = :id")
    int toggleEnabled(@Param("id") String id, @Param("now") Instant now);

    List<SkillDefinition> findByKnowledgeItemId(String knowledgeItemId);

    List<SkillDefinition> findByStage(String stage);

    List<SkillDefinition> findByEnabledTrue();

    List<SkillDefinition> findByStageAndEnabledTrue(String stage);

    List<SkillDefinition> findByEnabledFalse();

    List<SkillDefinition> findByStageAndEnabledFalse(String stage);
}
