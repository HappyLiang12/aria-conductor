package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * State-transition tests for {@link RunKanbanAutoCreator}: run lifecycle events
 * must create/move linked Kanban items with the exact field values expected by
 * the board (title derivation from the prompt seed, MEDIUM default priority,
 * TODO → IN_PROGRESS → DONE/CANCELLED transitions, terminal items untouched).
 */
@ExtendWith(MockitoExtension.class)
class RunKanbanAutoCreatorTest {

    @Mock private KanbanService kanbanService;
    @Mock private KanbanRepository kanbanRepository;
    @Mock private RunRepository runRepository;

    private RunKanbanAutoCreator creator;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        creator = new RunKanbanAutoCreator(kanbanService, kanbanRepository, runRepository);
    }

    // ---- onRunStarted ----

    @Test
    void onRunStarted_createsTodoItemTitledWithPromptSeed() {
        Run run = TestDataBuilder.aRun()
                .withId(runId).withAgentId(agentId)
                .withPromptSeed("Fix the flaky nightly build")
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        creator.onRunStarted(new RunStartedEvent(this, runId, agentId));

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        CreateKanbanItemRequest request = captor.getValue();
        assertThat(request.getTitle()).isEqualTo("Fix the flaky nightly build");
        assertThat(request.getPriority()).isEqualTo(KanbanPriority.MEDIUM);
        assertThat(request.getLinkedRunId()).isEqualTo(runId.toString());
        assertThat(request.getLinkedAgentId()).isEqualTo(agentId.toString());
    }

    @Test
    void onRunStarted_truncatesLongPromptSeedToSixtyChars() {
        String longSeed = "a".repeat(80);
        Run run = TestDataBuilder.aRun()
                .withId(runId).withAgentId(agentId).withPromptSeed(longSeed).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        creator.onRunStarted(new RunStartedEvent(this, runId, agentId));

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getTitle())
                .isEqualTo("a".repeat(57) + "...")
                .hasSize(60);
    }

    @ParameterizedTest(name = "blank seed [{0}] falls back to run-id title")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void onRunStarted_blankPromptSeed_fallsBackToRunIdTitle(String seed) {
        Run run = TestDataBuilder.aRun()
                .withId(runId).withAgentId(agentId).withPromptSeed(seed).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        creator.onRunStarted(new RunStartedEvent(this, runId, agentId));

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getTitle())
                .isEqualTo("Run: " + runId.toString().substring(0, 8));
    }

    @Test
    void onRunStarted_runNotFound_stillCreatesItemWithFallbackTitle() {
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        creator.onRunStarted(new RunStartedEvent(this, runId, agentId));

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getTitle())
                .isEqualTo("Run: " + runId.toString().substring(0, 8));
        assertThat(captor.getValue().getLinkedRunId()).isEqualTo(runId.toString());
    }

    @Test
    void onRunStarted_createFailure_isSwallowedAfterAttemptingCreation() {
        when(runRepository.findById(runId)).thenReturn(Optional.empty());
        when(kanbanService.create(any())).thenThrow(new IllegalStateException("board offline"));

        assertThatCode(() -> creator.onRunStarted(new RunStartedEvent(this, runId, agentId)))
                .doesNotThrowAnyException();

        // The creation was genuinely attempted with a well-formed request.
        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getLinkedRunId()).isEqualTo(runId.toString());
    }

    // ---- onRunIteration ----

    @Test
    void onRunIteration_movesOnlyTodoItemsToInProgress() {
        KanbanItem todoItem = kanbanItem("item-todo", KanbanStatus.TODO);
        KanbanItem inProgressItem = kanbanItem("item-wip", KanbanStatus.IN_PROGRESS);
        when(kanbanRepository.findByLinkedRunId(runId.toString()))
                .thenReturn(List.of(todoItem, inProgressItem));

        creator.onRunIteration(new RunIterationEvent(this, runId, agentId, 1, 50));

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<KanbanStatus> statusCaptor = ArgumentCaptor.forClass(KanbanStatus.class);
        ArgumentCaptor<String> commentCaptor = ArgumentCaptor.forClass(String.class);
        verify(kanbanService).transition(idCaptor.capture(), statusCaptor.capture(), commentCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo("item-todo");
        assertThat(statusCaptor.getValue()).isEqualTo(KanbanStatus.IN_PROGRESS);
        assertThat(commentCaptor.getValue()).isEqualTo("Run iteration started");
    }

    @Test
    void onRunIteration_noLinkedItems_performsNoTransition() {
        when(kanbanRepository.findByLinkedRunId(anyString())).thenReturn(List.of());

        creator.onRunIteration(new RunIterationEvent(this, runId, agentId, 1, 50));

        // Lookup used the run id as the linkage key; nothing was moved.
        ArgumentCaptor<String> lookupCaptor = ArgumentCaptor.forClass(String.class);
        verify(kanbanRepository).findByLinkedRunId(lookupCaptor.capture());
        assertThat(lookupCaptor.getValue()).isEqualTo(runId.toString());
        verify(kanbanService, never()).transition(anyString(), any(), anyString());
    }

    // ---- onRunCompleted ----

    @ParameterizedTest(name = "run {0} → kanban {1}")
    @CsvSource({
            "COMPLETED, DONE",
            "CANCELLED, CANCELLED",
            "FAILED,    CANCELLED",
            "PAUSED,    DONE"          // default branch of the status switch
    })
    void onRunCompleted_transitionsActiveItemToMappedStatus(RunStatus runStatus, KanbanStatus expected) {
        KanbanItem activeItem = kanbanItem("item-active", KanbanStatus.IN_PROGRESS);
        when(kanbanRepository.findByLinkedRunId(runId.toString())).thenReturn(List.of(activeItem));

        creator.onRunCompleted(new RunCompletedEvent(this, runId, agentId, runStatus));

        ArgumentCaptor<KanbanStatus> statusCaptor = ArgumentCaptor.forClass(KanbanStatus.class);
        ArgumentCaptor<String> commentCaptor = ArgumentCaptor.forClass(String.class);
        verify(kanbanService).transition(org.mockito.ArgumentMatchers.eq("item-active"),
                statusCaptor.capture(), commentCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(expected);
        assertThat(commentCaptor.getValue()).isEqualTo("Run " + runStatus);
    }

    @Test
    void onRunCompleted_leavesTerminalItemsUntouched() {
        KanbanItem doneItem = kanbanItem("item-done", KanbanStatus.DONE);
        KanbanItem cancelledItem = kanbanItem("item-cancelled", KanbanStatus.CANCELLED);
        when(kanbanRepository.findByLinkedRunId(runId.toString()))
                .thenReturn(List.of(doneItem, cancelledItem));

        creator.onRunCompleted(new RunCompletedEvent(this, runId, agentId, RunStatus.COMPLETED));

        verify(kanbanService, never()).transition(anyString(), any(), anyString());
        assertThat(doneItem.getStatus()).isEqualTo(KanbanStatus.DONE);
        assertThat(cancelledItem.getStatus()).isEqualTo(KanbanStatus.CANCELLED);
    }

    @Test
    void onRunCompleted_repositoryFailure_isSwallowed() {
        when(kanbanRepository.findByLinkedRunId(runId.toString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> creator.onRunCompleted(
                new RunCompletedEvent(this, runId, agentId, RunStatus.COMPLETED)))
                .doesNotThrowAnyException();
        verify(kanbanService, never()).transition(anyString(), any(), anyString());
    }

    // ---- helpers ----

    private KanbanItem kanbanItem(String id, KanbanStatus status) {
        return KanbanItem.builder()
                .id(id)
                .title("Item " + id)
                .status(status)
                .priority(KanbanPriority.MEDIUM)
                .linkedRunId(runId.toString())
                .build();
    }
}
