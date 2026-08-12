package io.aria.conductor.execution.dod;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DoDStageReviewRepository extends JpaRepository<DoDStageReview, String> {
    List<DoDStageReview> findByDodId(String dodId);

    List<DoDStageReview> findByDodIdAndStage(String dodId, String stage);

    List<DoDStageReview> findByDodIdAndStageOrderByReviewedAtDesc(String dodId, String stage);

    List<DoDStageReview> findByDodIdOrderByReviewedAtAsc(String dodId);
}
