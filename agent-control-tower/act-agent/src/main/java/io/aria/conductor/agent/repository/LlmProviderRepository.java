package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.LlmProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProvider, UUID> {
    Optional<LlmProvider> findByName(String name);
    Optional<LlmProvider> findByActiveTrue();
}
