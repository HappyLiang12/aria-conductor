package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.CoreCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoreCredentialRepository extends JpaRepository<CoreCredential, String> {
}
