package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {
}