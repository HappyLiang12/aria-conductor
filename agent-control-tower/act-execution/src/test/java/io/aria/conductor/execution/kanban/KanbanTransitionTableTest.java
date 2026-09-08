package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KanbanTransitionTableTest {

    private final KanbanService service = new KanbanService(null, null, null);

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
            "BACKLOG,CANCELLED"
    })
    void allowsSpecTransitions(String from, String to) {
        assertThat(service.isValidTransition(KanbanStatus.valueOf(from), KanbanStatus.valueOf(to))).isTrue();
    }

    @Test
    void terminalStatesHaveNoOutgoing() {
        assertThat(service.isValidTransition(KanbanStatus.DONE, KanbanStatus.TODO)).isFalse();
        assertThat(service.isValidTransition(KanbanStatus.CANCELLED, KanbanStatus.TODO)).isFalse();
    }

    @Test
    void blockedIsRetired() {
        assertThat(service.isValidTransition(KanbanStatus.BLOCKED, KanbanStatus.TODO)).isFalse();
    }
}
