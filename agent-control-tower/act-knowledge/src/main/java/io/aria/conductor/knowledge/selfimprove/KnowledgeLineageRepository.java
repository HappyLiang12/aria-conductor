package io.aria.conductor.knowledge.selfimprove;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeLineageRepository extends JpaRepository<KnowledgeLineage, String> {

    List<KnowledgeLineage> findByAncestorId(String ancestorId);

    List<KnowledgeLineage> findByDescendantId(String descendantId);
}
