package io.aria.conductor.execution.maintenance;

import java.time.Instant;
import java.util.UUID;

/**
 * Exact result of one executed bounded retirement (spec 7.2): every deleted and
 * unlinked count, never a summary that hides which rows went away.
 *
 * <p>The receipt is produced inside the same transaction as the deletion. The
 * counts of the set-based bulk deletes are the statement results those deletes
 * returned. The four agent-scoped counts -- {@code deletedAgents},
 * {@code deletedAgentPromptCalls}, {@code deletedAgentToolBindings} and
 * {@code deletedAgentSkillBindings} -- are the exact sizes of the
 * digest-verified frozen sets removed through repository APIs that return no
 * count ({@code JpaRepository} entity removal and per-agent void modifies); they
 * are captured in the same transaction, so no count is an estimate computed from
 * an earlier read.
 *
 * @param previewId the one-use preview this receipt belongs to
 * @param executedAt when the deletion committed (the injected clock)
 * @param deletedAgents agents whose current provider was {@code langchain}
 *        (frozen-set size; the removal API returns no count)
 * @param deletedRuns the runs owned by those agents
 * @param deletedApprovals the approvals of those runs
 * @param deletedAuditEvents audit rows deleted by verified resource type, frozen
 *        resource id and frozen row id
 * @param deletedPermissionRequests run-scoped permission-ledger rows
 * @param deletedTrajectories session-trajectory rows of those runs
 * @param deletedToolCalls tool-call rows of those runs
 * @param deletedRunPromptCalls prompt rows owned by those runs
 * @param deletedAgentPromptCalls prompt rows owned directly by those agents
 *        (frozen-set size; the removal API returns no count)
 * @param deletedRunBindings immutable run execution bindings of those runs
 * @param deletedAgentSessions agent-session rows keyed by those runs
 * @param deletedAgentToolBindings agent-to-tool bindings of those agents
 *        (frozen-set size; the per-agent modify returns no count)
 * @param deletedAgentSkillBindings agent-to-skill bindings of those agents
 *        (frozen-set size; the per-agent modify returns no count)
 * @param unlinkedKanbanCards shared cards whose link into the deleted scope was removed
 * @param unlinkedWorkflowSteps workflow steps whose agent or run link was removed
 */
public record RetirementReceipt(
        UUID previewId,
        Instant executedAt,
        int deletedAgents,
        int deletedRuns,
        int deletedApprovals,
        int deletedAuditEvents,
        int deletedPermissionRequests,
        int deletedTrajectories,
        int deletedToolCalls,
        int deletedRunPromptCalls,
        int deletedAgentPromptCalls,
        int deletedRunBindings,
        int deletedAgentSessions,
        int deletedAgentToolBindings,
        int deletedAgentSkillBindings,
        int unlinkedKanbanCards,
        int unlinkedWorkflowSteps) {

    /** True when the retirement had nothing left to delete or unlink. */
    public boolean isNoOp() {
        return deletedAgents == 0 && deletedRuns == 0 && deletedApprovals == 0
                && deletedAuditEvents == 0 && deletedPermissionRequests == 0
                && deletedTrajectories == 0 && deletedToolCalls == 0
                && deletedRunPromptCalls == 0 && deletedAgentPromptCalls == 0
                && deletedRunBindings == 0 && deletedAgentSessions == 0
                && deletedAgentToolBindings == 0 && deletedAgentSkillBindings == 0
                && unlinkedKanbanCards == 0 && unlinkedWorkflowSteps == 0;
    }
}
