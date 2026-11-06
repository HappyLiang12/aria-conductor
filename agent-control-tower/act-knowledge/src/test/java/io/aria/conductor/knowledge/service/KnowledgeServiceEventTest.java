package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.common.event.KnowledgeRetiredEvent;
import io.aria.conductor.common.event.KnowledgeSubmittedEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link KnowledgeService} publishes the expected
 * {@link org.springframework.context.ApplicationEvent}s when state changes
 * occur (submit / approve / retire).
 *
 * <p>Uses pure Mockito (matching the patterns in {@code ReportServiceTest}
 * and {@code KanbanServiceTest}) and asserts on a captured argument from the
 * mocked {@link ApplicationEventPublisher}.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceEventTest {

    @Mock
    KnowledgeItemRepository itemRepository;

    @Mock
    KnowledgeVersionRepository versionRepository;

    @Mock
    KnowledgeFileService fileService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    KnowledgeService service;

    private KnowledgeItem pendingItem;
    private KnowledgeVersion pendingVersion;

    @BeforeEach
    void seedItem() {
        UUID itemId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        pendingItem = KnowledgeItem.builder()
                .id(itemId)
                .name("Test Item")
                .type(KnowledgeType.PROMPT)
                .status(KnowledgeStatus.PENDING)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v0.1.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        pendingVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(itemId)
                .version("v0.1.0")
                .status(VersionStatus.PENDING)
                .content("body")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void submitKnowledge_publishesKnowledgeSubmittedEvent() {
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileService.storeContent(any(), any(), any(), any())).thenReturn("/tmp/test.md");

        CreateKnowledgeRequest request = CreateKnowledgeRequest.builder()
                .name("New Prompt")
                .type(KnowledgeType.PROMPT)
                .description("desc")
                .content("body")
                .build();

        service.submitKnowledge(request);

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(KnowledgeSubmittedEvent.class);
        KnowledgeSubmittedEvent event = (KnowledgeSubmittedEvent) captor.getValue();
        assertThat(event.getName()).isEqualTo("New Prompt");
    }

    @Test
    void reviewKnowledge_approve_publishesKnowledgeApprovedEvent() {
        when(itemRepository.findByIdForUpdate(pendingItem.getId())).thenReturn(Optional.of(pendingItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(pendingItem.getId(), "v0.1.0"))
                .thenReturn(Optional.of(pendingVersion));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewDecisionRequest request = ReviewDecisionRequest.builder()
                .decision(ReviewDecisionRequest.ReviewDecision.APPROVED)
                .reason("ok")
                .build();

        service.reviewKnowledge(pendingItem.getId(), request);

        ArgumentCaptor<KnowledgeApprovedEvent> captor =
                ArgumentCaptor.forClass(KnowledgeApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        KnowledgeApprovedEvent event = captor.getValue();
        assertThat(event.getKnowledgeId()).isEqualTo(pendingItem.getId());
        assertThat(event.getName()).isEqualTo("Test Item");
        assertThat(event.getType()).isEqualTo("PROMPT");
    }

    @Test
    void reviewKnowledge_reject_doesNotPublishApprovedEvent() {
        when(itemRepository.findByIdForUpdate(pendingItem.getId())).thenReturn(Optional.of(pendingItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(pendingItem.getId(), "v0.1.0"))
                .thenReturn(Optional.of(pendingVersion));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewDecisionRequest request = ReviewDecisionRequest.builder()
                .decision(ReviewDecisionRequest.ReviewDecision.REJECTED)
                .reason("nope")
                .build();

        service.reviewKnowledge(pendingItem.getId(), request);

        verify(eventPublisher, never()).publishEvent(any(KnowledgeApprovedEvent.class));
    }

    @Test
    void retireKnowledge_publishesKnowledgeRetiredEvent() {
        pendingItem.setStatus(KnowledgeStatus.APPROVED);
        when(itemRepository.findById(pendingItem.getId())).thenReturn(Optional.of(pendingItem));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.retireKnowledge(pendingItem.getId());

        ArgumentCaptor<KnowledgeRetiredEvent> captor =
                ArgumentCaptor.forClass(KnowledgeRetiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        KnowledgeRetiredEvent event = captor.getValue();
        assertThat(event.getKnowledgeId()).isEqualTo(pendingItem.getId());
        assertThat(event.getName()).isEqualTo("Test Item");
    }

    @Test
    void reviewKnowledge_alreadyDecided_isRejected() {
        // F1 guard: a review that observes a non-PENDING status (e.g. because a concurrent
        // reviewer already decided while holding the pessimistic row lock) is rejected, so
        // two opposing decisions can never both win.
        pendingItem.setStatus(KnowledgeStatus.APPROVED);
        when(itemRepository.findByIdForUpdate(pendingItem.getId())).thenReturn(Optional.of(pendingItem));

        ReviewDecisionRequest request = ReviewDecisionRequest.builder()
                .decision(ReviewDecisionRequest.ReviewDecision.REJECTED)
                .reason("late")
                .build();

        assertThatThrownBy(() -> service.reviewKnowledge(pendingItem.getId(), request))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
