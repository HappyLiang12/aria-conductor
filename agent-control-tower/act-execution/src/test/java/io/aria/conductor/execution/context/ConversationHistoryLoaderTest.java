package io.aria.conductor.execution.context;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one server-side history loader both transports share (spec §8.3): the
 * last 20 runs of the conversation, the last 200 turns of each (the bounded
 * queries already enforce that), reversed into turn order, filtered to
 * user/assistant turns, the failed-run note kept verbatim, and the most recent
 * 40 messages kept. The client history is only a fallback when the server has
 * none, and every bound it hits is reported as a degradation reason.
 */
class ConversationHistoryLoaderTest {

    private final RunRepository runs = mock(RunRepository.class);
    private final SessionTrajectoryRepository trajectories = mock(SessionTrajectoryRepository.class);
    private final ConversationHistoryLoader loader = new ConversationHistoryLoader(runs, trajectories);

    @Test
    void loadsAtMostTwentyRunsAndKeepsFortyMessages() {
        List<Run> newestFirst = IntStream.range(0, 25)
                .mapToObj(i -> run(i, RunStatus.COMPLETED))
                .toList();
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(newestFirst);
        // newest first, as the Desc repository returns them
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(any()))
                .thenReturn(IntStream.range(0, 200)
                        .mapToObj(i -> trajectory("user", "m" + (199 - i)))
                        .toList());

        List<ContextMessage> history = loader.load("c", List.of());

        assertThat(history).hasSize(40);
        // only a loader that reverses each run into turn order ends on the newest message
        assertThat(history.get(39).content()).isEqualTo("m199");
        verify(trajectories, times(20)).findTop200ByRunIdOrderByTurnNumberDesc(any());
    }

    @Test
    void theQueryBoundsAreNotDegradationReasonsTheLoaderCouldNeverObserve() {
        // The repository queries already cap at 20 runs and 200 turns per run, so
        // the loader can never see more: those reasons are not emitted.
        List<Run> newestFirst = IntStream.range(0, 25)
                .mapToObj(i -> run(i, RunStatus.COMPLETED))
                .toList();
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(newestFirst);
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(any()))
                .thenReturn(IntStream.range(0, 300)
                        .mapToObj(i -> trajectory("user", "m" + (299 - i)))
                        .toList());

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c", List.of());

        assertThat(loaded.messages()).hasSize(40);
        assertThat(loaded.degraded()).containsExactly("history:trimmed")
                .doesNotContain("history:run-bound", "history:turn-bound");
    }

    @Test
    void aNormalServerLoadRecordsNoDegradedReason() {
        Run older = run(0, RunStatus.COMPLETED);
        Run newer = run(1, RunStatus.COMPLETED);
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c"))
                .thenReturn(List.of(newer, older));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(older.getId()))
                .thenReturn(List.of(trajectory(1, "user", "question")));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(newer.getId()))
                .thenReturn(List.of(trajectory(1, "assistant", "answer")));

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c", List.of());

        assertThat(loaded.messages()).extracting(ContextMessage::content)
                .containsExactly("question", "answer");
        assertThat(loaded.degraded()).isEmpty();
    }

    @Test
    void anEmptyFallbackIsNotAClientHistoryDegradation() {
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(List.of());

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c", List.of());

        assertThat(loaded.messages()).isEmpty();
        assertThat(loaded.degraded()).isEmpty();
    }

    @Test
    void fallbackToolRowsDoNotConsumeTheFortyMessageCap() {
        List<ContextMessage> fallback = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            fallback.add(new ContextMessage("user", "f" + i, null));
        }
        fallback.add(new ContextMessage("tool", "tool output", "call-1"));

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded(null, fallback);

        List<ContextMessage> turns = loaded.messages().stream()
                .filter(message -> !"tool".equals(message.role()))
                .toList();
        assertThat(turns).hasSize(40);
        assertThat(turns.get(0).content()).isEqualTo("f5");
        assertThat(loaded.messages()).extracting(ContextMessage::content).contains("tool output");
    }

    @Test
    void aFortyOneMessageFallbackKeepsTheNewestForty() {
        List<ContextMessage> fallback = IntStream.range(0, 41)
                .mapToObj(i -> new ContextMessage("user", "f" + i, null))
                .toList();

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded(null, fallback);

        assertThat(loaded.messages()).hasSize(40);
        assertThat(loaded.messages().get(0).content()).isEqualTo("f1");
        assertThat(loaded.messages().get(39).content()).isEqualTo("f40");
    }

    @Test
    void reversesNewestFirstRowsIntoChronologicalOrder() {
        Run run = run(0, RunStatus.COMPLETED);
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(List.of(run));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(run.getId())).thenReturn(List.of(
                trajectory(3, "user", "third"),
                trajectory(2, "assistant", "second"),
                trajectory(1, "user", "first")));

        assertThat(loader.load("c", List.of())).extracting(ContextMessage::content)
                .containsExactly("first", "second", "third");
    }

    @Test
    void noConversationIdReturnsTheCappedFallback() {
        List<ContextMessage> fallback = IntStream.range(0, 50)
                .mapToObj(i -> new ContextMessage("user", "f" + i, null))
                .toList();

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded(null, fallback);

        assertThat(loaded.messages()).hasSize(40);
        assertThat(loaded.messages().get(39).content()).isEqualTo("f49");
        assertThat(loaded.degraded()).contains("history:client-history");
        verify(runs, never()).findTop20ByConversationIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void noServerRowsReturnsTheCappedClientFallbackAndSaysSo() {
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(List.of());
        List<ContextMessage> fallback = IntStream.range(0, 50)
                .mapToObj(i -> new ContextMessage("user", "f" + i, null))
                .toList();

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c", fallback);

        assertThat(loaded.messages()).hasSize(40);
        assertThat(loaded.messages().get(0).content()).isEqualTo("f10");
        assertThat(loaded.degraded()).contains("history:client-history");
    }

    @Test
    void serverRowsWinOverTheClientFallback() {
        Run run = run(0, RunStatus.COMPLETED);
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(List.of(run));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(run.getId()))
                .thenReturn(List.of(trajectory(1, "user", "server")));

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c",
                List.of(new ContextMessage("user", "client", null)));

        assertThat(loaded.messages()).extracting(ContextMessage::content).containsExactly("server");
        assertThat(loaded.degraded()).isEmpty();
    }

    @Test
    void aFailedRunKeepsItsSyntheticNoteAfterItsOwnTurns() {
        Run ok = run(0, RunStatus.COMPLETED);
        Run failed = run(1, RunStatus.FAILED);
        failed.setErrorMessage("boom");
        // newest first
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c"))
                .thenReturn(List.of(failed, ok));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(ok.getId()))
                .thenReturn(List.of(trajectory(1, "user", "ask")));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(failed.getId()))
                .thenReturn(List.of());

        assertThat(loader.load("c", List.of())).extracting(ContextMessage::content)
                .containsExactly("ask", "（系統註記：上一回合因「boom」失敗，未產生回覆。）");
    }

    @Test
    void onlyUserAndAssistantTurnsAreKept() {
        Run run = run(0, RunStatus.COMPLETED);
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(List.of(run));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(run.getId())).thenReturn(List.of(
                trajectory(4, "tool", "tool output"),
                trajectory(3, "system", "system row"),
                trajectory(2, "assistant", "answer"),
                trajectory(1, "user", "question")));

        assertThat(loader.load("c", List.of())).extracting(ContextMessage::content)
                .containsExactly("question", "answer");
    }

    @Test
    void theInFlightRunIsNotLoadedAsHistory() {
        Run inFlight = run(1, RunStatus.RUNNING);
        Run done = run(0, RunStatus.COMPLETED);
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c"))
                .thenReturn(List.of(inFlight, done));
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(done.getId()))
                .thenReturn(List.of(trajectory(1, "user", "earlier")));

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c", List.of());

        assertThat(loaded.messages()).extracting(ContextMessage::content).containsExactly("earlier");
        verify(trajectories, never()).findTop200ByRunIdOrderByTurnNumberDesc(inFlight.getId());
    }

    private static Run run(int age, RunStatus status) {
        return Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .status(status)
                .conversationId("c")
                .promptSeed("seed")
                .createdAt(Instant.parse("2026-11-09T00:00:00Z").plusSeconds(age))
                .build();
    }

    private static SessionTrajectory trajectory(String role, String content) {
        return trajectory(1, role, content);
    }

    private static SessionTrajectory trajectory(int turn, String role, String content) {
        return SessionTrajectory.builder()
                .runId(UUID.randomUUID())
                .turnNumber(turn)
                .role(role)
                .content(content)
                .build();
    }

    @Test
    void moreThanTwoHundredTurnsInARunKeepsTheNewestAndSaysOnlyTrimmed() {
        Run run = run(0, RunStatus.COMPLETED);
        when(runs.findTop20ByConversationIdOrderByCreatedAtDesc("c")).thenReturn(List.of(run));
        List<SessionTrajectory> rows = new ArrayList<>();
        for (int turn = 300; turn >= 1; turn--) {
            rows.add(trajectory(turn, "user", "t" + turn));
        }
        when(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(run.getId())).thenReturn(rows);

        ConversationHistoryLoader.LoadedHistory loaded = loader.loadBounded("c", List.of());

        assertThat(loaded.degraded()).containsExactly("history:trimmed");
        assertThat(loaded.messages()).hasSize(40);
        assertThat(loaded.messages().get(39).content()).isEqualTo("t300");
    }
}
