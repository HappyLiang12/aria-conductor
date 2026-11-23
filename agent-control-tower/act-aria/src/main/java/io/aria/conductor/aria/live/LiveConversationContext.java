package io.aria.conductor.aria.live;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Seeds the bridge from the same data the conversation timeline reads
 * ({@code AriaConversationController#getTimeline}): the conversation's runs by
 * {@code createdAt}, then their trajectories by run, turn number ascending.
 *
 * <p>Only {@code user}/{@code assistant} text rows become context — a tool row is not a
 * turn, and a blank row carries nothing to say. The last {@link LiveContextSource#MAX_ENTRIES}
 * of them are kept, so a long conversation is trimmed from the past rather than the present.
 * Any read failure degrades to an empty context: a missing conversation must not cost the
 * phone its {@code ready}.
 */
@Component
public class LiveConversationContext implements LiveContextSource {

    private static final Logger log = LoggerFactory.getLogger(LiveConversationContext.class);

    private final RunRepository runs;
    private final SessionTrajectoryRepository trajectories;

    public LiveConversationContext(RunRepository runs, SessionTrajectoryRepository trajectories) {
        this.runs = runs;
        this.trajectories = trajectories;
    }

    @Override
    public List<Entry> context(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            List<Run> conversationRuns = runs.findByConversationIdOrderByCreatedAtAsc(conversationId);
            if (conversationRuns.isEmpty()) {
                return List.of();
            }
            List<UUID> runIds = conversationRuns.stream().map(Run::getId).toList();
            Map<UUID, List<SessionTrajectory>> byRun = trajectories
                    .findByRunIdInOrderByTurnNumberAsc(runIds).stream()
                    .collect(Collectors.groupingBy(SessionTrajectory::getRunId));

            List<Entry> entries = new ArrayList<>();
            for (Run run : conversationRuns) {
                List<SessionTrajectory> rows = new ArrayList<>(
                        byRun.getOrDefault(run.getId(), List.of()));
                rows.sort(Comparator.comparingInt(SessionTrajectory::getTurnNumber));
                for (SessionTrajectory row : rows) {
                    if (!turn(row)) {
                        continue;
                    }
                    entries.add(new Entry(row.getRole(), row.getContent()));
                }
            }
            return entries.size() <= MAX_ENTRIES
                    ? List.copyOf(entries)
                    : List.copyOf(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
        } catch (RuntimeException e) {
            log.debug("aria live context unavailable: reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static boolean turn(SessionTrajectory row) {
        String role = row.getRole();
        String content = row.getContent();
        return ("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role))
                && content != null && !content.isBlank();
    }
}
