package io.aria.conductor.knowledge.scheduler;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Enforces review-deadline SLAs for pending knowledge items.
 * <p>
 * Sensitivity-based deadlines:
 * <ul>
 *   <li>RESTRICTED   → 24h</li>
 *   <li>CONFIDENTIAL → 48h</li>
 *   <li>INTERNAL     → 72h</li>
 *   <li>PUBLIC       → 72h</li>
 * </ul>
 * Behaviour: every 5 minutes, scan PENDING items whose review deadline has
 * passed, increment their {@code escalationCount}, log an escalation event,
 * and on the third escalation auto-REJECT. Auto-approval is forbidden by
 * design — only humans approve.
 */
@Component
public class ReviewSLAEnforcer {

    private static final Logger log = LoggerFactory.getLogger(ReviewSLAEnforcer.class);
    private static final int MAX_ESCALATIONS_BEFORE_REJECT = 3;

    private final KnowledgeItemRepository itemRepository;

    public ReviewSLAEnforcer(KnowledgeItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Scheduled(fixedRate = 300_000L) // every 5 minutes
    @Transactional
    public void enforceDeadlines() {
        List<KnowledgeItem> pending = itemRepository.findByStatus(KnowledgeStatus.PENDING);
        Instant now = Instant.now();
        for (KnowledgeItem item : pending) {
            Instant deadline = effectiveDeadline(item);
            if (deadline == null || deadline.isAfter(now)) {
                continue;
            }
            escalate(item);
        }
    }

    /**
     * Apply one escalation step to a single item, persisting the result.
     * Visible for tests.
     */
    @Transactional
    public KnowledgeItem escalate(KnowledgeItem item) {
        int next = item.getEscalationCount() == null ? 1 : item.getEscalationCount() + 1;
        item.setEscalationCount(next);
        log.warn("SLA breach for knowledge item {} (name={}, sensitivity={}); escalation #{}",
                item.getId(), item.getName(), item.getSensitivity(), next);

        if (next >= MAX_ESCALATIONS_BEFORE_REJECT) {
            // Mandatory: NEVER auto-approve. After exhausting escalations the
            // safe default is to reject so a human can re-submit.
            item.setStatus(KnowledgeStatus.REJECTED);
            item.setRejectionReason("Auto-rejected after " + next + " SLA escalations without review.");
            log.warn("Auto-REJECTING knowledge item {} after {} escalations (auto-approval is forbidden)",
                    item.getId(), next);
        }
        return itemRepository.save(item);
    }

    /**
     * Return the SLA deadline duration for a given sensitivity tier. The input
     * may be a {@link Sensitivity} enum value or its lower/upper-case string
     * form for compatibility with config-driven callers.
     */
    public Duration getDeadlineForSensitivity(String sensitivity) {
        if (sensitivity == null) return Duration.ofHours(72);
        return switch (sensitivity.toUpperCase()) {
            case "RESTRICTED" -> Duration.ofHours(24);
            case "CONFIDENTIAL" -> Duration.ofHours(48);
            case "INTERNAL", "PUBLIC" -> Duration.ofHours(72);
            default -> Duration.ofHours(72);
        };
    }

    public Duration getDeadlineForSensitivity(Sensitivity sensitivity) {
        return getDeadlineForSensitivity(sensitivity == null ? null : sensitivity.name());
    }

    /**
     * Resolve an item's effective deadline. Prefers the explicit
     * {@code reviewDeadline} field; falls back to created_at + sensitivity SLA.
     */
    public Instant effectiveDeadline(KnowledgeItem item) {
        if (item.getReviewDeadline() != null) {
            return item.getReviewDeadline();
        }
        if (item.getCreatedAt() == null) return null;
        return item.getCreatedAt().plus(getDeadlineForSensitivity(item.getSensitivity()));
    }
}
