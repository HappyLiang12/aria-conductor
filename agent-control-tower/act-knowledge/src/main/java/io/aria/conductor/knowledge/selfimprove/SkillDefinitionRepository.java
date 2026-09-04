package io.aria.conductor.knowledge.selfimprove;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, String> {

    List<SkillDefinition> findByKnowledgeItemId(String knowledgeItemId);

    boolean existsByName(String name);

    List<SkillDefinition> findByStage(String stage);

    List<SkillDefinition> findByEnabledTrue();

    List<SkillDefinition> findByStageAndEnabledTrue(String stage);

    List<SkillDefinition> findByEnabledFalse();

    List<SkillDefinition> findByStageAndEnabledFalse(String stage);
}
