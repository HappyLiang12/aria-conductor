package io.aria.conductor.knowledge.git;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeSubmissionIntentRepository
        extends JpaRepository<KnowledgeSubmissionIntent, String> {

    List<KnowledgeSubmissionIntent> findByStatusIn(List<KnowledgeSubmissionIntent.Status> statuses);

    List<KnowledgeSubmissionIntent> findByItemId(String itemId);
}
