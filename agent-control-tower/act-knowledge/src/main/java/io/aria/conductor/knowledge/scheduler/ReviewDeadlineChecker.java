package io.aria.conductor.knowledge.scheduler;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class ReviewDeadlineChecker {

    private static final Logger log = LoggerFactory.getLogger(ReviewDeadlineChecker.class);
    private static final Duration REVIEW_DEADLINE = Duration.ofHours(72);

    private final KnowledgeItemRepository itemRepository;

    public ReviewDeadlineChecker(KnowledgeItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Scheduled(fixedRate = 300_000) // Every 5 minutes
    public void checkPendingReviews() {
        Instant cutoff = Instant.now().minus(REVIEW_DEADLINE);
        List<KnowledgeItem> overdueItems = itemRepository
                .findByStatusAndCreatedAtBefore(KnowledgeStatus.PENDING, cutoff);

        if (!overdueItems.isEmpty()) {
            log.warn("Found {} knowledge items pending review for over 72 hours:", overdueItems.size());
            for (KnowledgeItem item : overdueItems) {
                log.warn("  - [{}] {} (id={}, submitted={})",
                        item.getType(), item.getName(), item.getId(), item.getCreatedAt());
            }
        }
    }
}
