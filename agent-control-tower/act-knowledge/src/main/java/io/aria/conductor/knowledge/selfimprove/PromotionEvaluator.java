package io.aria.conductor.knowledge.selfimprove;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stage-aware promotion gatekeeper for the self-improvement maturity ladder.
 * <p>
 * The evaluator never <em>performs</em> a promotion — it only computes
 * eligibility. Actual writes happen in {@code SelfImprovementService} after
 * a positive decision. Anti-gaming checks are mandatory and shared across
 * all stages.
 */
@Service
public class PromotionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PromotionEvaluator.class);

    /** Stage 2 threshold values (≥3 calls, cos > 0.85, ≥2 agents, ≥2 sessions). */
    public static final int STAGE2_MIN_CALLS = 3;
    public static final double STAGE2_COSINE_FLOOR = 0.85;

    /** Stage 3+ usage thresholds. */
    public static final int STAGE3_MIN_USES = 5;
    public static final int STAGE4_MIN_USES = 10;

    /** Stage 5 composition threshold. */
    public static final int STAGE5_MIN_SCRIPTS = 3;

    /** Anti-gaming knobs. */
    public static final double DOMINANCE_LIMIT = 0.60;
    public static final Duration RATE_LIMIT = Duration.ofHours(1);

    private final SimilarityEngine similarityEngine;
    private final SkillDefinitionRepository skillRepository;
    private final KnowledgeItemRepository knowledgeRepository;
    private final PromptCallRepository promptCallRepository;

    /** Per-user last auto-promotion timestamp — drives the rate-limit check. */
    private final Map<String, Instant> lastPromotionByUser = new HashMap<>();

    public PromotionEvaluator(SimilarityEngine similarityEngine,
                              SkillDefinitionRepository skillRepository,
                              KnowledgeItemRepository knowledgeRepository,
                              PromptCallRepository promptCallRepository) {
        this.similarityEngine = similarityEngine;
        this.skillRepository = skillRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.promptCallRepository = promptCallRepository;
    }

    /** Stage 1 → 2 (reusable prompt). */
    public PromotionDecision evaluateForStage2(List<PromptCall> candidates) {
        return evaluateForStage2(candidates, null);
    }

    /**
     * Variant exposing the {@code userId} so the rate-limit anti-gaming gate
     * can apply. Tests use this overload to inject identity.
     */
    public PromotionDecision evaluateForStage2(List<PromptCall> candidates, String userId) {
        if (candidates == null || candidates.size() < STAGE2_MIN_CALLS) {
            return reject("REUSABLE_PROMPT",
                    "Need at least " + STAGE2_MIN_CALLS + " similar calls, got "
                            + (candidates == null ? 0 : candidates.size()));
        }
        // Defensive cosine check: compute the lowest pairwise similarity in
        // the cluster — must stay above the Stage 2 floor.
        double worst = worstPairwiseSimilarity(candidates);
        if (worst < STAGE2_COSINE_FLOOR) {
            return reject("REUSABLE_PROMPT",
                    "Cluster cosine " + fmt(worst) + " below floor " + STAGE2_COSINE_FLOOR);
        }
        if (!passesDominanceCheck(candidates)) {
            return reject("REUSABLE_PROMPT",
                    "Anti-gaming: a single agent contributed > "
                            + (int) (DOMINANCE_LIMIT * 100) + "%");
        }
        PromotionContext ctx = contextFor(candidates, userId);
        if (!passesAntiGaming(ctx)) {
            return reject("REUSABLE_PROMPT", "Anti-gaming check failed");
        }
        return PromotionDecision.approve("REUSABLE_PROMPT",
                "Stage 2 criteria met: " + candidates.size() + " calls, cos>="
                        + fmt(worst) + ", agents=" + ctx.agentIds().size()
                        + ", sessions=" + ctx.sessionIds().size(),
                null);
    }

    /** Stage 2 → 3 (skill). */
    public PromotionDecision evaluateForStage3(KnowledgeItem reusablePrompt) {
        if (reusablePrompt == null) return reject("SKILL", "Null reusable prompt");
        int uses = usageCountOf(reusablePrompt);
        if (uses < STAGE3_MIN_USES) {
            return reject("SKILL",
                    "Need " + STAGE3_MIN_USES + " uses, got " + uses);
        }
        if (reusablePrompt.getStatus() != KnowledgeStatus.APPROVED) {
            return reject("SKILL", "Source must be APPROVED, was " + reusablePrompt.getStatus());
        }
        if (isDuplicateOfApproved(reusablePrompt)) {
            return reject("SKILL", "Duplicate of an existing approved item (cosine > 0.95)");
        }
        return PromotionDecision.approve("SKILL",
                "Stage 3 criteria met: " + uses + " uses on approved prompt",
                reusablePrompt);
    }

    /** Stage 3 → 4 (script/tool). */
    public PromotionDecision evaluateForStage4(KnowledgeItem skill) {
        if (skill == null) return reject("SCRIPT", "Null skill");
        int uses = usageCountOf(skill);
        if (uses < STAGE4_MIN_USES) {
            return reject("SCRIPT",
                    "Need " + STAGE4_MIN_USES + " uses, got " + uses);
        }
        if (skill.getStatus() != KnowledgeStatus.APPROVED) {
            return reject("SCRIPT", "Skill must be APPROVED, was " + skill.getStatus());
        }
        // 2-reviewer-approval proxy: reviewerId must be set and rejection
        // must not have been raised. Tests-pass is enforced by
        // SelfImprovementService running SandboxExecutor at promote time.
        if (skill.getReviewerId() == null || skill.getRejectionReason() != null) {
            return reject("SCRIPT", "Skill lacks reviewer approval");
        }
        return PromotionDecision.approve("SCRIPT",
                "Stage 4 criteria met: " + uses + " uses, reviewer-approved", skill);
    }

    /** Stage 4 → 5 (workflow template). */
    public PromotionDecision evaluateForStage5(List<KnowledgeItem> scripts) {
        if (scripts == null || scripts.size() < STAGE5_MIN_SCRIPTS) {
            return reject("WORKFLOW",
                    "Need " + STAGE5_MIN_SCRIPTS + " scripts, got "
                            + (scripts == null ? 0 : scripts.size()));
        }
        for (KnowledgeItem s : scripts) {
            if (s.getStatus() != KnowledgeStatus.APPROVED) {
                return reject("WORKFLOW",
                        "Script " + s.getId() + " not APPROVED");
            }
        }
        return PromotionDecision.approve("WORKFLOW",
                "Stage 5 criteria met: " + scripts.size() + " approved scripts composed",
                null);
    }

    /**
     * Anti-gaming checks shared by all promotions:
     * <ul>
     *   <li>Diversity — ≥2 agents and ≥2 sessions in the source set.</li>
     *   <li>Rate limit — at most one auto-promotion per user per hour.</li>
     * </ul>
     * Dominance (no single agent > 60%) requires per-call counts and is
     * checked separately in {@link #passesDominanceCheck(List)}. Visible
     * for tests.
     */
    boolean passesAntiGaming(PromotionContext context) {
        if (context == null) return false;
        if (context.agentIds() == null || context.agentIds().size() < 2) {
            log.debug("Anti-gaming: <2 distinct agents");
            return false;
        }
        if (context.sessionIds() == null || context.sessionIds().size() < 2) {
            log.debug("Anti-gaming: <2 distinct sessions");
            return false;
        }
        Instant last = context.lastPromotionTime();
        if (last == null && context.userId() != null) {
            last = lastPromotionByUser.get(context.userId());
        }
        if (last != null) {
            Duration since = Duration.between(last, Instant.now());
            if (since.compareTo(RATE_LIMIT) < 0) {
                log.debug("Anti-gaming: rate-limited (last promotion {}s ago)", since.toSeconds());
                return false;
            }
        }
        return true;
    }

    /**
     * Dominance gate: no single agent may contribute more than
     * {@link #DOMINANCE_LIMIT} of the evidence.
     */
    boolean passesDominanceCheck(List<PromptCall> calls) {
        if (calls == null || calls.isEmpty()) return false;
        Map<String, Integer> counts = new HashMap<>();
        for (PromptCall c : calls) {
            String aid = c.getAgentId() == null ? "" : c.getAgentId().toString();
            counts.merge(aid, 1, Integer::sum);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double share = (double) max / (double) calls.size();
        return share <= DOMINANCE_LIMIT;
    }

    /** Record that {@code userId} just had a successful auto-promotion. */
    public void recordAutoPromotion(String userId) {
        if (userId != null) lastPromotionByUser.put(userId, Instant.now());
    }

    /** Last auto-promotion time for {@code userId} (or {@code null}). */
    public Instant lastAutoPromotionFor(String userId) {
        return userId == null ? null : lastPromotionByUser.get(userId);
    }

    /**
     * Periodic sweep: bucket recent prompt calls into similarity clusters
     * and log Stage 2 candidates. The sweep <em>does not</em> auto-promote
     * — promotion is performed by {@code SelfImprovementService} which
     * always creates PENDING items requiring human review.
     */
    @Scheduled(fixedRate = 300_000L)
    public void scheduledEvaluation() {
        List<PromptCall> recent = promptCallRepository.findAll();
        if (recent.isEmpty()) return;
        List<List<PromptCall>> clusters =
                similarityEngine.findSimilarClusters(recent, STAGE2_COSINE_FLOOR);
        for (List<PromptCall> cluster : clusters) {
            if (cluster.size() < STAGE2_MIN_CALLS) continue;
            PromotionDecision d = evaluateForStage2(cluster);
            if (d.approved()) {
                log.info("Stage-2 candidate: {} calls, reason='{}'",
                        cluster.size(), d.reason());
            }
        }
    }

    // ---- helpers -------------------------------------------------------

    PromotionContext contextFor(List<PromptCall> calls, String userId) {
        Set<String> agents = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        for (PromptCall c : calls) {
            String aid = c.getAgentId() == null ? "" : c.getAgentId().toString();
            String sid = c.getRunId() == null ? "" : c.getRunId().toString();
            agents.add(aid);
            sessions.add(sid);
        }
        Instant last = userId == null ? null : lastPromotionByUser.get(userId);
        return new PromotionContext(agents, sessions, userId, last);
    }

    private double worstPairwiseSimilarity(List<PromptCall> cluster) {
        if (cluster.size() < 2) return 1.0;
        double worst = 1.0;
        for (int i = 0; i < cluster.size(); i++) {
            for (int j = i + 1; j < cluster.size(); j++) {
                double sim = similarityEngine.cosineSimilarity(
                        signature(cluster.get(i)), signature(cluster.get(j)));
                if (sim < worst) worst = sim;
            }
        }
        return worst;
    }

    private String signature(PromptCall c) {
        StringBuilder sb = new StringBuilder();
        if (c.getProvider() != null) sb.append(c.getProvider()).append(' ');
        if (c.getModel() != null) sb.append(c.getModel()).append(' ');
        if (c.getOutcome() != null) sb.append(c.getOutcome()).append(' ');
        if (c.getToolsUsed() != null) sb.append(c.getToolsUsed().replace(',', ' '));
        return sb.toString();
    }

    int usageCountOf(KnowledgeItem item) {
        if (item == null || item.getId() == null) return 0;
        List<SkillDefinition> linked = skillRepository.findByKnowledgeItemId(item.getId().toString());
        return linked.stream().mapToInt(SkillDefinition::getUsageCount).max().orElse(0);
    }

    private boolean isDuplicateOfApproved(KnowledgeItem candidate) {
        List<KnowledgeItem> approved = new java.util.ArrayList<>(
                knowledgeRepository.findByStatus(KnowledgeStatus.APPROVED));
        // Exclude self by id when present.
        approved.removeIf(k -> Objects.equals(k.getId(), candidate.getId()));
        String text = (candidate.getName() == null ? "" : candidate.getName()) + " "
                + (candidate.getDescription() == null ? "" : candidate.getDescription());
        return similarityEngine.isDuplicate(text, approved);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    private static PromotionDecision reject(String stage, String reason) {
        return new PromotionDecision(false, stage, reason, null);
    }

    /** Outcome of an evaluation. */
    public record PromotionDecision(
            boolean approved,
            String stage,
            String reason,
            KnowledgeItem promotedItem) {

        public static PromotionDecision approve(String stage, String reason, KnowledgeItem item) {
            return new PromotionDecision(true, stage, reason, item);
        }
    }

    /** Context fed into {@link #passesAntiGaming(PromotionContext)}. */
    public record PromotionContext(
            Set<String> agentIds,
            Set<String> sessionIds,
            String userId,
            Instant lastPromotionTime) {
    }

    /** Convenience UUID parser for callers that have string ids. */
    public static UUID parseId(String s) {
        return s == null ? null : UUID.fromString(s);
    }
}
