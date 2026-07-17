package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.SessionTrajectory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SessionTrajectoryRepository extends JpaRepository<SessionTrajectory, UUID> {
    List<SessionTrajectory> findByRunIdOrderByTurnNumberAsc(UUID runId);

    @Query("SELECT COALESCE(MAX(t.turnNumber), 0) FROM SessionTrajectory t WHERE t.runId = :runId")
    int findMaxTurnNumberByRunId(@Param("runId") UUID runId);

    List<SessionTrajectory> findByRunIdInOrderByTurnNumberAsc(List<UUID> runIds);
    void deleteByRunIdIn(List<UUID> runIds);
}