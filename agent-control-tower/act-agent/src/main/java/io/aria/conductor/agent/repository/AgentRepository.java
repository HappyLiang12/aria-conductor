package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {
    List<Agent> findByHealthStatusNot(HealthStatus status);
    List<Agent> findByAgentType(AgentType type);
    Optional<Agent> findByName(String name);
    List<Agent> findByRole(String role);
    long countByHealthStatus(HealthStatus status);

    /**
     * Refreshes only the two columns the reconciler owns. A bulk update
     * deliberately bypasses {@code Agent.onUpdate()}, so a per-minute reconcile
     * does not rewrite {@code updated_at} and destroy the "agent was edited"
     * signal.
     *
     * <p>{@code @Transactional} is mandatory, not decoration: the caller is a
     * scheduled method with no transaction of its own, and a {@code @Modifying}
     * custom query does not open one — without it the update fails with
     * "Executing an update/delete query" and the reconcile silently stops
     * working. The transaction is scoped to this single row so one agent's
     * failure cannot roll back the rest of the sweep.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Agent a set a.healthStatus = :status, a.lastProbedAt = :probedAt where a.id = :id")
    int reconcileHealth(@Param("id") UUID id,
                        @Param("status") HealthStatus status,
                        @Param("probedAt") Instant probedAt);
}
