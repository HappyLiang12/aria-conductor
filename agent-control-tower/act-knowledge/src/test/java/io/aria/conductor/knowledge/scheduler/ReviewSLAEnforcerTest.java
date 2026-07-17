package io.aria.conductor.knowledge.scheduler;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewSLAEnforcerTest {

    @Mock
    KnowledgeItemRepository itemRepository;

    ReviewSLAEnforcer enforcer;

    @BeforeEach
    void setUp() {
        enforcer = new ReviewSLAEnforcer(itemRepository);
        lenient().when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void getDeadlineForSensitivity_appliesCorrectTiers() {
        assertThat(enforcer.getDeadlineForSensitivity("RESTRICTED")).isEqualTo(Duration.ofHours(24));
        assertThat(enforcer.getDeadlineForSensitivity("CONFIDENTIAL")).isEqualTo(Duration.ofHours(48));
        assertThat(enforcer.getDeadlineForSensitivity("INTERNAL")).isEqualTo(Duration.ofHours(72));
        assertThat(enforcer.getDeadlineForSensitivity("PUBLIC")).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void getDeadlineForSensitivity_acceptsLowerCase() {
        assertThat(enforcer.getDeadlineForSensitivity("restricted")).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void getDeadlineForSensitivity_defaults72hForUnknownOrNull() {
        assertThat(enforcer.getDeadlineForSensitivity((String) null)).isEqualTo(Duration.ofHours(72));
        assertThat(enforcer.getDeadlineForSensitivity("WAT")).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void getDeadlineForSensitivity_acceptsEnum() {
        assertThat(enforcer.getDeadlineForSensitivity(Sensitivity.RESTRICTED)).isEqualTo(Duration.ofHours(24));
        assertThat(enforcer.getDeadlineForSensitivity((Sensitivity) null)).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void escalate_incrementsCount() {
        KnowledgeItem item = pendingItem(Sensitivity.INTERNAL, Instant.now().minus(Duration.ofHours(73)));
        item.setEscalationCount(0);

        KnowledgeItem out = enforcer.escalate(item);

        assertThat(out.getEscalationCount()).isEqualTo(1);
        assertThat(out.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
    }

    @Test
    void escalate_atThird_autoRejects_neverApproves() {
        KnowledgeItem item = pendingItem(Sensitivity.INTERNAL, Instant.now().minus(Duration.ofHours(73)));
        item.setEscalationCount(2);

        KnowledgeItem out = enforcer.escalate(item);

        assertThat(out.getEscalationCount()).isEqualTo(3);
        assertThat(out.getStatus()).isEqualTo(KnowledgeStatus.REJECTED);
        assertThat(out.getRejectionReason()).contains("SLA escalations");
        // Sanity: explicitly assert NOT approved.
        assertThat(out.getStatus()).isNotEqualTo(KnowledgeStatus.APPROVED);
    }

    @Test
    void enforceDeadlines_skipsItemsBeforeDeadline() {
        KnowledgeItem fresh = pendingItem(Sensitivity.INTERNAL, Instant.now().minus(Duration.ofHours(1)));
        when(itemRepository.findByStatus(KnowledgeStatus.PENDING)).thenReturn(List.of(fresh));

        enforcer.enforceDeadlines();

        verify(itemRepository, never()).save(any(KnowledgeItem.class));
    }

    @Test
    void enforceDeadlines_escalatesOverdueItems() {
        KnowledgeItem overdue = pendingItem(Sensitivity.RESTRICTED, Instant.now().minus(Duration.ofHours(25)));
        overdue.setEscalationCount(0);
        when(itemRepository.findByStatus(KnowledgeStatus.PENDING)).thenReturn(List.of(overdue));

        enforcer.enforceDeadlines();

        verify(itemRepository, times(1)).save(any(KnowledgeItem.class));
        assertThat(overdue.getEscalationCount()).isEqualTo(1);
    }

    @Test
    void enforceDeadlines_usesExplicitReviewDeadlineWhenSet() {
        KnowledgeItem item = pendingItem(Sensitivity.INTERNAL, Instant.now().minus(Duration.ofHours(1)));
        item.setReviewDeadline(Instant.now().minus(Duration.ofMinutes(5))); // already past
        when(itemRepository.findByStatus(KnowledgeStatus.PENDING)).thenReturn(List.of(item));

        enforcer.enforceDeadlines();

        assertThat(item.getEscalationCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void effectiveDeadline_fallsBackToCreatedAtPlusSensitivity() {
        KnowledgeItem item = pendingItem(Sensitivity.CONFIDENTIAL, Instant.parse("2026-01-01T00:00:00Z"));

        Instant deadline = enforcer.effectiveDeadline(item);

        assertThat(deadline).isEqualTo(Instant.parse("2026-01-03T00:00:00Z")); // +48h
    }

    private KnowledgeItem pendingItem(Sensitivity sensitivity, Instant createdAt) {
        return KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("k")
                .type(KnowledgeType.PROMPT)
                .status(KnowledgeStatus.PENDING)
                .sensitivity(sensitivity)
                .currentVersion("v0.1.0")
                .createdAt(createdAt)
                .escalationCount(0)
                .build();
    }
}
