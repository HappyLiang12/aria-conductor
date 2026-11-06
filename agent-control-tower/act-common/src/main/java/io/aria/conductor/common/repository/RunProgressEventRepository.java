package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RunProgressEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RunProgressEventRepository extends JpaRepository<RunProgressEventEntity, UUID> {

    List<RunProgressEventEntity> findByRunIdOrderBySeqAsc(UUID runId);

    List<RunProgressEventEntity> findByRunIdAndSeqAfterOrderBySeqAsc(UUID runId, Long afterSeq);

    @Modifying
    @Query("DELETE FROM RunProgressEventEntity p WHERE p.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
