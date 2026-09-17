package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RuntimeCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RuntimeCredentialRepository extends JpaRepository<RuntimeCredential, String> {

    Optional<RuntimeCredential> findByProviderId(String providerId);
}
