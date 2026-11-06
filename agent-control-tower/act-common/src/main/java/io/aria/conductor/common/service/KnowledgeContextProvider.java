package io.aria.conductor.common.service;

import io.aria.conductor.common.model.KnowledgeItem;
import java.util.List;

public interface KnowledgeContextProvider {
    List<KnowledgeItem> getApprovedKnowledgeContext(int limit);

    /**
     * Returns a formatted "## Knowledge Context" block for direct system-prompt
     * injection. Single source of truth for the format used by both AriaService
     * and AgentLoopEngine.
     *
     * @param limit max number of approved items to include
     * @return formatted prompt block, or "" when no approved items exist
     */
    String buildKnowledgeContextPrompt(int limit);
}
