package io.aria.conductor.common.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextQueryTextTest {

    @Test
    void goalAndOnlyTheLastThreeUserTurnsAreKept() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), "c", UUID.randomUUID(), ContextAdapter.CORE, 5,
                "goal text", null, null, 24_000, "D:/repo", "",
                List.of(
                        new ContextMessage("user", "turn-1", null),
                        new ContextMessage("user", "turn-2", null),
                        new ContextMessage("user", "turn-3", null),
                        new ContextMessage("assistant", "skip", null),
                        new ContextMessage("user", "turn-4", null),
                        new ContextMessage("user", "turn-5", null)));

        assertThat(ContextQueryText.of(request))
                .contains("goal text", "turn-3", "turn-4", "turn-5")
                .doesNotContain("turn-1", "turn-2", "skip");
    }

    @Test
    void nullRequestIsEmpty() {
        assertThat(ContextQueryText.of(null)).isEmpty();
    }
}
