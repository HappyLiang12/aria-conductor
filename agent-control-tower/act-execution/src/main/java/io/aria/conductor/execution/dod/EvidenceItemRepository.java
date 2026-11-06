package io.aria.conductor.execution.dod;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, String> {
    List<EvidenceItem> findByTaskId(String taskId);

    List<EvidenceItem> findByDodId(String dodId);

    List<EvidenceItem> findByTaskIdOrderByCreatedAtDesc(String taskId);
}
