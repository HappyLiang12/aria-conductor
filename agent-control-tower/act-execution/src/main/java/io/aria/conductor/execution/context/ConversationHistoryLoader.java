package io.aria.conductor.execution.context;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The one server-side conversation history loader (spec §8.3): the last 20 runs
 * of a conversation, the last 200 turns of each (both bounded by the repository
 * queries, never a full load sliced in memory), reversed into turn order, the
 * failed-run note kept verbatim after that run's turns, and the most recent 40
 * user/assistant messages kept.
 *
 * <p>The client history is a fallback only when the server has no usable rows
 * for the conversation, capped at 40 user/assistant messages (tool rows never
 * consume a slot); the two are never concatenated. {@code history:client-history}
 * is recorded only when that fallback actually contributes messages.
 *
 * <p>A run that has not reached a terminal status is the conversation's
 * in-flight head: its rows are *this* run's own timeline, which the engine
 * appends after the history, so loading it here would duplicate the current
 * request. Those runs are skipped.
 */
@Component
public class ConversationHistoryLoader {

    /** P6 bounds: runs per conversation and turns per run the queries already enforce. */
    static final int MAX_RUNS = 20;
    static final int MAX_TURNS_PER_RUN = 200;
    static final int MAX_MESSAGES = 40;
    /** Max characters of a failed run's error message carried into the note. */
    static final int MAX_ERROR_NOTE_CHARS = 200;

    private static final Set<RunStatus> TERMINAL = Set.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    private final RunRepository runs;
    private final SessionTrajectoryRepository trajectories;

    public ConversationHistoryLoader(RunRepository runs, SessionTrajectoryRepository trajectories) {
        this.runs = runs;
        this.trajectories = trajectories;
    }

    /** What the loader kept, plus every bound it had to enforce. */
    public record LoadedHistory(List<ContextMessage> messages, List<String> degraded) {
        public LoadedHistory {
            messages = List.copyOf(messages);
            degraded = List.copyOf(degraded);
        }
    }

    public List<ContextMessage> load(String conversationId, List<ContextMessage> clientFallback) {
        return loadBounded(conversationId, clientFallback).messages();
    }

    public LoadedHistory loadBounded(String conversationId, List<ContextMessage> clientFallback) {
        List<ContextMessage> fallback = clientFallback == null ? List.of() : clientFallback;
        if (conversationId == null || conversationId.isBlank()) {
            return fallbackHistory(fallback);
        }

        List<Run> newestFirst = runs.findTop20ByConversationIdOrderByCreatedAtDesc(conversationId);
        if (newestFirst == null || newestFirst.isEmpty()) {
            return fallbackHistory(fallback);
        }

        // The queries bound the load (20 runs, 200 turns each), so the loader can
        // never observe more and has no reason to report a bound it did not hit.
        List<String> degraded = new ArrayList<>();
        List<Run> prior = newestFirst.stream()
                .filter(run -> TERMINAL.contains(run.getStatus()))
                .limit(MAX_RUNS)
                .toList();
        if (prior.isEmpty()) {
            return fallbackHistory(fallback);
        }

        List<Run> chronological = new ArrayList<>(prior);
        Collections.reverse(chronological);

        List<ContextMessage> history = new ArrayList<>();
        for (Run run : chronological) {
            List<SessionTrajectory> rows = trajectories
                    .findTop200ByRunIdOrderByTurnNumberDesc(run.getId());
            if (rows == null) {
                rows = List.of();
            }
            // newest first -> turn order
            for (int i = Math.min(rows.size(), MAX_TURNS_PER_RUN) - 1; i >= 0; i--) {
                SessionTrajectory row = rows.get(i);
                String role = row.getRole();
                String content = row.getContent();
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                if (content == null || content.isBlank()) {
                    continue;
                }
                history.add(new ContextMessage(role, content, null));
            }
            if (run.getStatus() == RunStatus.FAILED) {
                history.add(new ContextMessage("assistant", failedTurnNote(run.getErrorMessage()), null));
            }
        }

        if (history.isEmpty()) {
            return fallbackHistory(fallback);
        }
        if (history.size() > MAX_MESSAGES) {
            degraded.add("history:trimmed");
        }
        return new LoadedHistory(mostRecent(history), degraded);
    }

    /**
     * The client-provided transcript, capped at {@link #MAX_MESSAGES}. Only a
     * fallback that actually contributes messages degrades: an empty transcript
     * (a conversation's first turn) is normal, not a fallback.
     */
    private static LoadedHistory fallbackHistory(List<ContextMessage> fallback) {
        List<ContextMessage> capped = mostRecent(fallback);
        return new LoadedHistory(capped,
                capped.isEmpty() ? List.of() : List.of("history:client-history"));
    }

    /**
     * The most recent {@link #MAX_MESSAGES} user/assistant messages, in
     * chronological order, plus every tool row that trails them. Tool rows are
     * results of the turns around them, so they never consume a cap slot.
     */
    private static List<ContextMessage> mostRecent(List<ContextMessage> history) {
        int counted = 0;
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            start = i;
            if (!"tool".equals(history.get(i).role())) {
                counted++;
                if (counted == MAX_MESSAGES) {
                    break;
                }
            }
        }
        return new ArrayList<>(history.subList(start, history.size()));
    }

    /**
     * ONE synthetic assistant message telling the next turn the previous one
     * failed. Copied verbatim from {@code AriaService.failedTurnNote} so the
     * transcript reads identically whichever transport assembled it; A2 removes
     * the Aria-side copy.
     */
    static String failedTurnNote(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "（系統註記：上一回合失敗，未產生回覆。）";
        }
        String clipped = errorMessage.length() > MAX_ERROR_NOTE_CHARS
                ? errorMessage.substring(0, MAX_ERROR_NOTE_CHARS)
                : errorMessage;
        return "（系統註記：上一回合因「" + clipped + "」失敗，未產生回覆。）";
    }
}
