package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KanbanTransitionTableTest {

    // Null collaborators are safe here: isValidTransition only reads the static
    // transition table, so none of the injected beans are ever touched.
    private final KanbanService service = new KanbanService(null, null, null, null, null, null);

    @ParameterizedTest
    @CsvSource({
            "BACKLOG,TODO",
            "TODO,IN_PROGRESS",
            "TODO,BACKLOG",
            "IN_PROGRESS,TODO",
            "IN_PROGRESS,BACKLOG",
            "IN_PROGRESS,REVIEW",
            "IN_PROGRESS,DONE",
            "REVIEW,IN_PROGRESS",
            "REVIEW,TODO",
            "REVIEW,DONE",
            "TODO,CANCELLED",
            "IN_PROGRESS,CANCELLED",
            "REVIEW,CANCELLED",
            "BACKLOG,CANCELLED",
            // Operator-reported defect D3: a finished card must be re-doable.
            // Redo re-enters the flow at Backlog or Todo; it never jumps back
            // into execution directly.
            "DONE,BACKLOG",
            "DONE,TODO"
    })
    void allowsSpecTransitions(String from, String to) {
        assertThat(service.isValidTransition(KanbanStatus.valueOf(from), KanbanStatus.valueOf(to))).isTrue();
    }

    @Test
    void redoTargetsAreTheOnlyWayOutOfDone() {
        assertThat(service.isValidTransition(KanbanStatus.DONE, KanbanStatus.IN_PROGRESS)).isFalse();
        assertThat(service.isValidTransition(KanbanStatus.DONE, KanbanStatus.REVIEW)).isFalse();
        assertThat(service.isValidTransition(KanbanStatus.DONE, KanbanStatus.CANCELLED)).isFalse();
    }

    @Test
    void cancelledRemainsTerminal() {
        assertThat(service.isValidTransition(KanbanStatus.CANCELLED, KanbanStatus.TODO)).isFalse();
        assertThat(service.isValidTransition(KanbanStatus.CANCELLED, KanbanStatus.BACKLOG)).isFalse();
    }

    @Test
    void blockedIsRetired() {
        assertThat(service.isValidTransition(KanbanStatus.BLOCKED, KanbanStatus.TODO)).isFalse();
    }
}
