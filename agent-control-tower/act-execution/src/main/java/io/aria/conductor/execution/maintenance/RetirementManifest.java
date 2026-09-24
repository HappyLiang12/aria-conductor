package io.aria.conductor.execution.maintenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Read-only, expiring preview of one bounded legacy retirement (spec 7.2): the
 * exact ID sets the operator is about to hard-delete together with a SHA-256
 * digest over those sets.
 *
 * <p>The digest is order-independent and covers every frozen set, so
 * {@code execute} can prove two things before deleting anything: the caller
 * presents the digest of the preview it reviewed, and the freshly re-derived
 * selection still digests to the same value. A changed selection (a new legacy
 * agent, a vanished run, a different child row) therefore fails the digest
 * comparison instead of silently widening or narrowing the deletion.
 *
 * <p>The manifest is memory-only and never persisted: a preview cannot outlive
 * the process that produced it, and an expired manifest is refused rather than
 * refreshed.
 *
 * @param previewId   the one-use preview identity the operator passes to execute
 * @param digest      lowercase hex SHA-256 over the exact ID sets below
 * @param createdAt   when the preview was taken
 * @param expiresAt   when {@code execute} stops accepting this manifest
 * @param agentIds    exactly the agents whose current provider is {@code langchain}
 * @param runIds      exactly the runs owned by those agents
 * @param approvalIds exactly the approvals of those runs
 * @param auditEventIds exactly the audit rows of the frozen {@code (type, id)} sets
 * @param permissionRequestIds the run-scoped permission-ledger rows of those runs
 * @param trajectoryIds the session-trajectory rows of those runs
 * @param toolCallIds   the tool-call rows of those runs
 * @param promptCallIds the run-owned <em>and</em> directly agent-owned prompt rows
 * @param agentSessionRunIds the agent-session keys (run UUIDs) of those runs
 * @param agentToolBindingIds the composite {@code agentId:toolId} bindings of those agents
 * @param agentSkillBindingIds the composite {@code agentId:skillId} bindings of those agents
 * @param kanbanCardIds the cards whose agent or run link points into the frozen sets
 * @param workflowChainIds the chains whose step agent or run links point into the frozen sets
 */
public record RetirementManifest(
        UUID previewId,
        String digest,
        Instant createdAt,
        Instant expiresAt,
        Set<UUID> agentIds,
        Set<UUID> runIds,
        Set<UUID> approvalIds,
        Set<Long> auditEventIds,
        Set<UUID> permissionRequestIds,
        Set<UUID> trajectoryIds,
        Set<UUID> toolCallIds,
        Set<Long> promptCallIds,
        Set<UUID> agentSessionRunIds,
        Set<String> agentToolBindingIds,
        Set<String> agentSkillBindingIds,
        Set<String> kanbanCardIds,
        Set<UUID> workflowChainIds) {

    public RetirementManifest {
        Objects.requireNonNull(previewId, "previewId is required");
        Objects.requireNonNull(digest, "digest is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        agentIds = Set.copyOf(agentIds);
        runIds = Set.copyOf(runIds);
        approvalIds = Set.copyOf(approvalIds);
        auditEventIds = Set.copyOf(auditEventIds);
        permissionRequestIds = Set.copyOf(permissionRequestIds);
        trajectoryIds = Set.copyOf(trajectoryIds);
        toolCallIds = Set.copyOf(toolCallIds);
        promptCallIds = Set.copyOf(promptCallIds);
        agentSessionRunIds = Set.copyOf(agentSessionRunIds);
        agentToolBindingIds = Set.copyOf(agentToolBindingIds);
        agentSkillBindingIds = Set.copyOf(agentSkillBindingIds);
        kanbanCardIds = Set.copyOf(kanbanCardIds);
        workflowChainIds = Set.copyOf(workflowChainIds);
    }

    /** True when this manifest is past its expiry at {@code now}. */
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Canonical, order-independent SHA-256 over the exact ID sets. Each set is
     * emitted on its own line with lexicographically sorted members, so any
     * membership change -- and nothing else -- changes the digest.
     *
     * <p>The previewId, timestamps and counts are deliberately not part of the
     * digest: it identifies the target selection, not the preview session.
     */
    public static String digestOf(Collection<UUID> agentIds, Collection<UUID> runIds,
                                  Collection<UUID> approvalIds, Collection<Long> auditEventIds,
                                  Collection<UUID> permissionRequestIds,
                                  Collection<UUID> trajectoryIds, Collection<UUID> toolCallIds,
                                  Collection<Long> promptCallIds, Collection<UUID> agentSessionRunIds,
                                  Collection<String> agentToolBindingIds,
                                  Collection<String> agentSkillBindingIds,
                                  Collection<String> kanbanCardIds,
                                  Collection<UUID> workflowChainIds) {
        StringBuilder canonical = new StringBuilder()
                .append("agents=").append(sorted(agentIds)).append('\n')
                .append("runs=").append(sorted(runIds)).append('\n')
                .append("approvals=").append(sorted(approvalIds)).append('\n')
                .append("auditEvents=").append(sortedNumbers(auditEventIds)).append('\n')
                .append("permissionRequests=").append(sorted(permissionRequestIds)).append('\n')
                .append("trajectories=").append(sorted(trajectoryIds)).append('\n')
                .append("toolCalls=").append(sorted(toolCallIds)).append('\n')
                .append("promptCalls=").append(sortedNumbers(promptCallIds)).append('\n')
                .append("agentSessions=").append(sorted(agentSessionRunIds)).append('\n')
                .append("agentTools=").append(sortedStrings(agentToolBindingIds)).append('\n')
                .append("agentSkills=").append(sortedStrings(agentSkillBindingIds)).append('\n')
                .append("kanbanCards=").append(sortedStrings(kanbanCardIds)).append('\n')
                .append("workflowChains=").append(sorted(workflowChainIds)).append('\n');
        return sha256Hex(canonical.toString());
    }

    private static List<String> sorted(Collection<UUID> ids) {
        TreeSet<String> sorted = new TreeSet<>();
        for (UUID id : ids) {
            sorted.add(id.toString());
        }
        return new ArrayList<>(sorted);
    }

    private static List<String> sortedNumbers(Collection<Long> ids) {
        TreeSet<String> sorted = new TreeSet<>();
        for (Long id : ids) {
            sorted.add(String.valueOf(id));
        }
        return new ArrayList<>(sorted);
    }

    private static List<String> sortedStrings(Collection<String> ids) {
        return new ArrayList<>(new TreeSet<>(ids));
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for the retirement digest", e);
        }
    }
}
