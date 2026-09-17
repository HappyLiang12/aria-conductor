package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KanbanServiceTest {

    private static final UUID LINKED_RUN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    KanbanRepository repository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    RunRepository runRepository;

    @Mock
    ApprovalRepository approvalRepository;

    @Mock
    AgentRepository agentRepository;

    @Mock
    AgentPickupEligibility pickupEligibility;

    @InjectMocks
    KanbanService service;

    private KanbanItem stored;

    @BeforeEach
    void initStoredItem() {
        stored = KanbanItem.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .title("Existing")
                .status(KanbanStatus.TODO)
                .priority(KanbanPriority.MEDIUM)
                .build();
    }

    @Test
    void create_persistsItemWithDefaults() {
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("New task")
                .description("desc")
                .build();

        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.create(request);

        ArgumentCaptor<KanbanItem> captor = ArgumentCaptor.forClass(KanbanItem.class);
        verify(repository).save(captor.capture());
        KanbanItem saved = captor.getValue();

        assertThat(saved.getTitle()).isEqualTo("New task");
        assertThat(saved.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(saved.getPriority()).isEqualTo(KanbanPriority.MEDIUM);
        assertThat(result).isSameAs(saved);
    }

    @Test
    void create_withLinkedRunId_persistsLink() {
        // A card that observes an existing run: the create path validates the
        // link resolves, and skips eligibility entirely.
        UUID runId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("Linked")
                .linkedRunId(runId.toString())
                .priority(KanbanPriority.HIGH)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder().id(runId).build()));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.create(request);

        assertThat(result.getLinkedRunId()).isEqualTo(runId.toString());
        assertThat(result.getPriority()).isEqualTo(KanbanPriority.HIGH);
    }

    @Test
    void list_withStatusFilter_callsFindByStatus() {
        when(repository.findByStatus(KanbanStatus.TODO)).thenReturn(List.of(stored));

        List<KanbanItem> result = service.list(KanbanStatus.TODO);

        assertThat(result).hasSize(1).containsExactly(stored);
        verify(repository).findByStatus(KanbanStatus.TODO);
        verify(repository, never()).findAll();
    }

    @Test
    void list_withoutStatus_returnsAll() {
        when(repository.findAll()).thenReturn(List.of(stored));

        List<KanbanItem> result = service.list(null);

        assertThat(result).containsExactly(stored);
        verify(repository).findAll();
    }

    @Test
    void transition_validTodoToInProgress_succeeds() {
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, "starting");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
    }

    @Test
    void transition_doneToInProgress_throws() {
        stored.setStatus(KanbanStatus.DONE);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid kanban transition");

        verify(repository, never()).save(any());
    }

    @Test
    void transition_cancelledIsTerminal() {
        stored.setStatus(KanbanStatus.CANCELLED);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.TODO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transition_blockedToTodo_isRejected() {
        // BLOCKED is retired: no outgoing transitions; V52 migrated rows to REVIEW.
        stored.setStatus(KanbanStatus.BLOCKED);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.TODO, "unblocked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid kanban transition");
    }

    @Test
    void transition_inProgressToReview_succeeds() {
        stored.setStatus(KanbanStatus.IN_PROGRESS);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.REVIEW, "waiting for approval");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.REVIEW);
    }

    @Test
    void transition_reviewToInProgress_succeeds() {
        stored.setStatus(KanbanStatus.REVIEW);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, "approved");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
    }

    @Test
    void transition_reviewToDone_succeeds() {
        stored.setStatus(KanbanStatus.REVIEW);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.DONE, "approved");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.DONE);
    }

    @Test
    void doneIsRejectedWhileTheLinkedRunIsPaused() {
        // A paused run has not finished, and Done skips the only path that
        // terminates it: the card would orphan a run that is still resumable.
        when(runRepository.findById(LINKED_RUN_ID)).thenReturn(Optional.of(
                Run.builder().id(LINKED_RUN_ID).status(RunStatus.PAUSED).build()));

        assertThatThrownBy(() -> service.guardLinkedRunNotActive(LINKED_RUN_ID.toString()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code()).isEqualTo("LINKED_RUN_ACTIVE"))
                .satisfies(e -> assertThat(((PickupRejectedException) e).details())
                        .containsEntry("runId", LINKED_RUN_ID.toString())
                        .containsEntry("runStatus", "PAUSED"));
    }

    @Test
    void doneIsRejectedWhileTheLinkedRunIsStillActive() {
        // Pins the whole active set: dropping any of these silently re-opens
        // DONE-orphans-a-live-run.
        for (RunStatus status : List.of(RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.RUNNING)) {
            when(runRepository.findById(LINKED_RUN_ID)).thenReturn(Optional.of(
                    Run.builder().id(LINKED_RUN_ID).status(status).build()));

            assertThatThrownBy(() -> service.guardLinkedRunNotActive(LINKED_RUN_ID.toString()))
                    .as("DONE must be refused while the linked run is %s", status)
                    .isInstanceOf(PickupRejectedException.class)
                    .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                            .isEqualTo("LINKED_RUN_ACTIVE"));
        }
    }

    @Test
    void doneIsAllowedWhenTheRunIsMissing() {
        when(runRepository.findById(LINKED_RUN_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.guardLinkedRunNotActive(LINKED_RUN_ID.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void doneIsAllowedWhenTheLinkIsUnparseable() {
        // A blemished card stays closable: an unreadable link is history, not a
        // run to protect, so Done is not blocked on it — and not even looked up.
        assertThatCode(() -> service.guardLinkedRunNotActive("not-a-run-id"))
                .doesNotThrowAnyException();
        verify(runRepository, never()).findById(any());
    }

    @Test
    void doneGuardLookupFailurePropagates() {
        // Swallowing it would permit Done on a link nobody could read — exactly
        // how a live run gets orphaned.
        when(runRepository.findById(LINKED_RUN_ID))
                .thenThrow(new DataAccessResourceFailureException("run lookup down"));

        assertThatThrownBy(() -> service.guardLinkedRunNotActive(LINKED_RUN_ID.toString()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void transitionToReviewWithACorruptLinkIsRejectedAndLeavesTheCardWhereItWas() {
        // The review ask is keyed on the linked run: moving first would leave the
        // card in Review with its status changed and no decision surface.
        stored.setStatus(KanbanStatus.IN_PROGRESS);
        stored.setLinkedRunId("not-a-run-id");
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.REVIEW, null))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code()).isEqualTo("CORRUPT_RUN_LINK"))
                .satisfies(e -> assertThat(((PickupRejectedException) e).details())
                        .containsEntry("linkedRunId", "not-a-run-id"));

        assertThat(stored.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        verify(repository, never()).save(any());
        // No event, so no listener can attach a review ask to a moved card.
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void transitionToReviewWithALinkThatResolvesToNoRunIsAllowed() {
        // Parses but absent: nothing to guard, and the ask is still created
        // (informational, run facts unknown).
        stored.setStatus(KanbanStatus.IN_PROGRESS);
        stored.setLinkedRunId(LINKED_RUN_ID.toString());
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.transition(stored.getId(), KanbanStatus.REVIEW, null).getStatus())
                .isEqualTo(KanbanStatus.REVIEW);
    }

    @Test
    void transitionToReviewWithABlankLinkIsAllowed() {
        // Blank is legitimately "no run", not a corrupt one: the ask is skipped.
        stored.setStatus(KanbanStatus.IN_PROGRESS);
        stored.setLinkedRunId("   ");
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.transition(stored.getId(), KanbanStatus.REVIEW, null).getStatus())
                .isEqualTo(KanbanStatus.REVIEW);
    }

    @Test
    void transition_todoToReview_isRejected() {
        stored.setStatus(KanbanStatus.TODO);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.REVIEW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid kanban transition");

        verify(repository, never()).save(any());
    }

    @Test
    void get_missingId_throwsNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_partialFields_mergesIntoExisting() {
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateKanbanItemRequest request = UpdateKanbanItemRequest.builder()
                .assignee("alice")
                .priority(KanbanPriority.HIGH)
                .build();

        KanbanItem result = service.update(stored.getId(), request);

        assertThat(result.getAssignee()).isEqualTo("alice");
        assertThat(result.getPriority()).isEqualTo(KanbanPriority.HIGH);
        // Title unchanged.
        assertThat(result.getTitle()).isEqualTo("Existing");
    }
}
