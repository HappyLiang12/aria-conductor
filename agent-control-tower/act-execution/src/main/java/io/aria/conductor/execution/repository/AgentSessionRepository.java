package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    /** Housekeeping S1: single-statement bulk delete (run_id is the PK here). */
    @Modifying
    @Query("DELETE FROM AgentSession s WHERE s.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}