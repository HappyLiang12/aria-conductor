package io.aria.conductor.aria.repository;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * The committed run state the synchronous Aria chat polls (F2/F6).
 *
 * <p>Two reasons this is a projection rather than {@code RunRepository.findById}:
 * open-in-view binds the request's persistence context, so {@code findById} keeps
 * serving the PENDING instance this request saved; and the reply must be able to
 * state <em>why</em> a run failed, which needs the stable error identity, not just
 * the narrative output. The query always issues its own SELECT.
 *
 * <p>Deliberately a {@link Repository} marker (not {@code JpaRepository}): this
 * read-only seam must not grow a second write path to the run row, which the
 * engine's conditional single-statement writers own.
 */
@org.springframework.stereotype.Repository
public interface AriaRunStateRepository extends Repository<Run, UUID> {

    @Query("SELECT r.status AS status, r.finalOutput AS finalOutput, "
            + "r.errorCode AS errorCode, r.errorMessage AS errorMessage "
            + "FROM Run r WHERE r.id = :id")
    Optional<RunSnapshot> findSnapshotById(@Param("id") UUID id);

    interface RunSnapshot {
        RunStatus getStatus();

        String getFinalOutput();

        String getErrorCode();

        String getErrorMessage();
    }
}
