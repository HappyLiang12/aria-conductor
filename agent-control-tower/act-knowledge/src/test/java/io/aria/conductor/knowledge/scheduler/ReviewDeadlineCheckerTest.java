package io.aria.conductor.knowledge.scheduler;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.aria.conductor.test.TestDataBuilder.aKnowledgeItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewDeadlineCheckerTest {

    @Mock
    KnowledgeItemRepository itemRepository;

    ReviewDeadlineChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ReviewDeadlineChecker(itemRepository);
    }

    @Test
    void checkPendingReviews_queriesPendingItemsOlderThan72Hours() {
        Instant before = Instant.now();
        when(itemRepository.findByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of());

        checker.checkPendingReviews();

        Instant after = Instant.now();
        ArgumentCaptor<KnowledgeStatus> statusCaptor = ArgumentCaptor.forClass(KnowledgeStatus.class);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(itemRepository).findByStatusAndCreatedAtBefore(
                statusCaptor.capture(), cutoffCaptor.capture());

        // only PENDING items are swept
        assertThat(statusCaptor.getValue()).isEqualTo(KnowledgeStatus.PENDING);
        // cutoff is exactly "now - 72h" (bounded by the instants around the call)
        assertThat(cutoffCaptor.getValue())
                .isAfterOrEqualTo(before.minus(Duration.ofHours(72)))
                .isBeforeOrEqualTo(after.minus(Duration.ofHours(72)));
    }

    @Test
    void checkPendingReviews_overdueItems_areReportedButNeverMutatedOrSaved() {
        KnowledgeItem overdue = aKnowledgeItem()
                .withStatus(KnowledgeStatus.PENDING)
                .withCreatedAt(Instant.now().minus(Duration.ofHours(100)))
                .build();
        when(itemRepository.findByStatusAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(overdue));

        checker.checkPendingReviews();

        // the sweep is read-only: no save/delete, item state untouched
        verify(itemRepository).findByStatusAndCreatedAtBefore(any(), any());
        verifyNoMoreInteractions(itemRepository);
        assertThat(overdue.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(overdue.getRetiredAt()).isNull();
    }

    @Test
    void checkPendingReviews_noOverdueItems_performsSingleQueryOnly() {
        when(itemRepository.findByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of());

        checker.checkPendingReviews();

        verify(itemRepository).findByStatusAndCreatedAtBefore(any(), any());
        verifyNoMoreInteractions(itemRepository);
    }
}
