package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.ToolPack;
import io.aria.conductor.common.model.VersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ToolPackRepository extends JpaRepository<ToolPack, String> {

    Optional<ToolPack> findByName(String name);

    List<ToolPack> findByStatus(VersionStatus status);

    List<ToolPack> findByEnabledTrue();
}
