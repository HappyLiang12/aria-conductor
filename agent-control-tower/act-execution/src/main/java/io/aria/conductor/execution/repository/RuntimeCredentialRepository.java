package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.RuntimeCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Store of managed runtime credentials (spec 6.1). Resolution loads one
 * credential by the reference recorded in the run binding; management is
 * scoped to the single operator credential of an installation, so no
 * per-agent or per-pack query surface exists.
 */
@Repository
public interface RuntimeCredentialRepository extends JpaRepository<RuntimeCredential, String> {

    List<RuntimeCredential> findByCoreId(String coreId);
}
