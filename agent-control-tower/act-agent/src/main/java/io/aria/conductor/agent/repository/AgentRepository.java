package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
