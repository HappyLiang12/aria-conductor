package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aria conversation helpers outside the chat turn itself. Chat runs on the SSE
 * path ({@link AriaStreamService}); this service composes the one-click
 * synthesis prompt for a dispatch batch.
 */
@Service
public class AriaService {

    private final RunRepository runRepository;

    public AriaService(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    /** One-click synthesis template (Feature B4): fixed header, one line per child, fixed tail. */
    private static final String SYNTHESIS_PROMPT_HEADER =
            "以下子任務已完成，請彙整結果並給我建議報告與下一步：";
    private static final String SYNTHESIS_PROMPT_TAIL =
            "請先讀取需要的子任務結果（用你的 run 工具），以繁體中文輸出：完成/失敗統計、各子任務重點、整體建議、仍無法核實之事項。";

    /**
     * Compose (but never run) the one-click synthesis prompt for a dispatch batch
     * (Feature B4). With a {@code dispatchedByRunId} the group is loaded directly;
     * otherwise the conversation's latest group — the newest run that dispatched
     * at least one child — is used. Empty when nothing resolves; the controller
     * maps that to the module's usual 404.
     */
    public Optional<String> composeSynthesisPrompt(String conversationId, UUID dispatchedByRunId) {
        List<Run> children = dispatchedByRunId != null
                ? runRepository.findByDispatchedByRunId(dispatchedByRunId)
                : findLatestDispatchGroup(conversationId);
        if (children.isEmpty()) {
            return Optional.empty();
        }
        List<Run> ordered = children.stream()
                .sorted(Comparator.comparing(Run::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        StringBuilder prompt = new StringBuilder(SYNTHESIS_PROMPT_HEADER).append('\n');
        for (Run child : ordered) {
            prompt.append("- run ").append(child.getId()).append('：')
                    .append(child.getStatus()).append('\n');
        }
        prompt.append(SYNTHESIS_PROMPT_TAIL);
        return Optional.of(prompt.toString());
    }

    /**
     * The children of the conversation's latest dispatch group: walk the runs
     * newest-first and take the first that dispatched any child.
     */
    private List<Run> findLatestDispatchGroup(String conversationId) {
        List<Run> runs = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        for (int i = runs.size() - 1; i >= 0; i--) {
            List<Run> children = runRepository.findByDispatchedByRunId(runs.get(i).getId());
            if (!children.isEmpty()) {
                return children;
            }
        }
        return List.of();
    }
}
