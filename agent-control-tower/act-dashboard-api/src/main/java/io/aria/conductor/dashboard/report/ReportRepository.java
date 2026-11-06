package io.aria.conductor.dashboard.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportRepository extends JpaRepository<ReportArtifact, String> {

    List<ReportArtifact> findByOwner(String owner);

    List<ReportArtifact> findBySourceRunId(String runId);

    List<ReportArtifact> findAllByOrderByCreatedAtDesc();
}
