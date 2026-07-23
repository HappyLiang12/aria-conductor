package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.PackCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackCredentialRepository extends JpaRepository<PackCredential, String> {

    List<PackCredential> findByPackId(String packId);

    Optional<PackCredential> findByPackIdAndAgentIdAndCredKey(String packId, String agentId, String credKey);

    Optional<PackCredential> findByPackIdAndAgentIdIsNullAndCredKey(String packId, String credKey);

    void deleteByPackId(String packId);
}
