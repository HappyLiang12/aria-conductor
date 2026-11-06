package io.aria.conductor.aria.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduledJobRepository extends JpaRepository<ScheduledJobEntity, String> {

    List<ScheduledJobEntity> findByStatus(String status);

    List<ScheduledJobEntity> findByCategory(String category);

    List<ScheduledJobEntity> findByCategoryAndStatus(String category, String status);

    List<ScheduledJobEntity> findByStatusOrderByNextFireAtAsc(String status);
}
