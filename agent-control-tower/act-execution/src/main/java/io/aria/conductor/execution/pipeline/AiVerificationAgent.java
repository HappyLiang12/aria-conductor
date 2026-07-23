package io.aria.conductor.execution.pipeline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stage 3 of the action execution pipeline — AI safety verification agent.
 *
 * <p>Calls the configured LLM with a structured "is this action safe?" prompt
 * for HIGH_RISK / EXECUTE actions. LOW-risk READ/WRITE actions skip the call
 * entirely and return PASS.
 *
 * <p><strong>Profile-driven self-verification.</strong> When the active
 * {@link HarnessProfile} enables self-verify escalation (non-empty
 * {@code escalateTiers}), the reviewer may return ESCALATE, which the pipeline
 * routes to a human approval gate — letting the LLM decide, per action, whether
 * a move is risky/likely-wrong and needs HITL, even when the action would not
 * otherwise require approval. ESCALATE is honoured only for the profile's opted-in
 * risk tiers; otherwise it is downgraded to WARN.
 *
 * <p><strong>Fire-and-forget safety net</strong>: any LLM failure (no client
 * configured, network error, malformed response, …) returns PASS so the
 * pipeline never blocks on infrastructure issues. The caller can still rely
 * on Stage 2 (rules) and Stage 4 (human approval) for hard guarantees.
 *
 * <p>Per-turn batching: results for a given (runId, iterationCount, action.id())
 * tuple are cached (bounded, TTL-evicted) so the same turn does not pay for
 * repeated LLM calls when an action is re-evaluated.
 */
@Slf4j
@Service
public class AiVerificationAgent {

    /** Default maximum tokens reserved for the safety verification response. */
    private static final int MAX_RESPONSE_TOKENS = 200;

    private static final String SYSTEM_PROMPT = """
            You are a safety reviewer for an autonomous AI agent's tool calls.
            Given an action (tool name, parameters, run context), assess whether the
            action is safe to execute right now.

            Respond on the FIRST line with exactly one of: PASS, FAIL, or WARN.
            On the SECOND line, give a one-sentence reason.

            Use FAIL only for clearly destructive, exfiltrating, or policy-violating
            actions. Use WARN when there is ambiguity but the action is likely safe.
            Default to PASS when unsure.
            """;

    /** ESCALATE-aware prompt used when the profile opts into self-verify escalation. */
    private static final String SYSTEM_PROMPT_WITH_ESCALATE = """
            You are a safety reviewer for an autonomous AI agent's tool calls.
            Given an action (tool name, parameters, risk tier, run context), assess whether the
            action is safe to execute right now.

            Respond on the FIRST line with exactly one of: PASS, FAIL, WARN, or ESCALATE.
            On the SECOND line, give a one-sentence reason.

            Use FAIL only for clearly destructive, exfiltrating, or policy-violating actions.
            Use ESCALATE when the action looks risky, likely wrong, or high-impact and a human
            should approve it before it runs (e.g. pushing code, overwriting files, irreversible
            or hard-to-undo operations). Use WARN when there is minor ambiguity but the action is
            likely safe. Default to PASS when unsure and low-impact.
            """;

    private final ObjectProvider<LlmClient> llmClientProvider;
    private final ToolRiskResolver riskResolver;

    // Bounded, TTL-evicted per-turn cache (was an unbounded ConcurrentHashMap).
    private final Cache<String, AiVerificationResult> turnCache = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public AiVerificationAgent(ObjectProvider<LlmClient> llmClientProvider, ToolRiskResolver riskResolver) {
        this.llmClientProvider = llmClientProvider;
        this.riskResolver = riskResolver;
    }

    /**
     * AI-based safety review.
     *
     * <ul>
     *     <li>LOW-classified READ/WRITE actions return PASS without calling the LLM.</li>
     *     <li>HIGH_RISK / EXECUTE / non-LOW actions invoke the LLM.</li>
     *     <li>ESCALATE is honoured only for the profile's opted-in risk tiers.</li>
     *     <li>Any LLM error returns PASS (fire-and-forget safety net).</li>
     * </ul>
     */
    public AiVerificationResult verify(Action action, ActionClassification classification, RunContext ctx) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(classification, "classification");

        HarnessProfile profile = (ctx != null && ctx.getHarnessProfile() != null)
                ? ctx.getHarnessProfile() : HarnessProfile.defaults();

        if (!profile.selfVerify().enabled() || !shouldInvokeLlm(action, classification)) {
            log.debug("AI verification skipped for action '{}'", action.name());
            return AiVerificationResult.pass("Skipped: low risk");
        }

        String cacheKey = buildCacheKey(ctx, action);
        AiVerificationResult cached = turnCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("AI verification cache hit for action '{}': {}", action.name(), cached.outcome());
            return cached;
        }

        LlmClient client = llmClientProvider.getIfAvailable();
        if (client == null) {
            log.info("AI verification skipped — no LLM client available");
            return AiVerificationResult.pass("Skipped: LLM unavailable");
        }

        AiVerificationResult result;
        try {
            result = callLlm(client, action, classification, ctx, profile);
            result = gateEscalation(result, action, profile);
        } catch (Exception e) {
            // Fire-and-forget: NEVER let LLM failures crash the pipeline.
            log.warn("AI verification failed for action '{}' (non-fatal, defaulting to PASS): {}",
                    action.name(), e.getMessage());
            result = AiVerificationResult.pass("Skipped: LLM error " + e.getClass().getSimpleName());
        }

        turnCache.put(cacheKey, result);
        return result;
    }

    /**
     * Visible for tests — clear the per-turn result cache.
     */
    public void clearCache() {
        turnCache.invalidateAll();
    }

    private boolean shouldInvokeLlm(Action action, ActionClassification classification) {
        // Per spec: HIGH_RISK and EXECUTE always go to AI; others only if non-LOW risk.
        if (action.type() == ActionType.HIGH_RISK || action.type() == ActionType.EXECUTE) {
            return true;
        }
        String level = classification.riskLevel();
        return level != null && !"LOW".equalsIgnoreCase(level);
    }

    /** ESCALATE is honoured only for tiers the profile opts into; otherwise downgrade to WARN. */
    private AiVerificationResult gateEscalation(AiVerificationResult result, Action action, HarnessProfile profile) {
        if (result == null || !result.isEscalate()) return result;
        String tier = safeTier(action);
        if (profile.canEscalateTier(tier)) {
            log.info("AI verification ESCALATE for action '{}' (tier {}) — routing to human approval",
                    action.name(), tier);
            return result;
        }
        log.debug("Downgrading ESCALATE to WARN for action '{}' (tier {} not in escalate set)",
                action.name(), tier);
        return AiVerificationResult.warn(
                "De-escalated (" + tier + " not gated): " + result.reasoning(), result.confidence());
    }

    private AiVerificationResult callLlm(LlmClient client, Action action,
                                         ActionClassification classification, RunContext ctx,
                                         HarnessProfile profile) {
        String systemPrompt = resolveSystemPrompt(profile);
        int maxTokens = profile.selfVerify().maxResponseTokens() > 0
                ? profile.selfVerify().maxResponseTokens() : MAX_RESPONSE_TOKENS;
        String userPrompt = buildPrompt(action, classification, ctx);
        LlmRequest request = LlmRequest.of(null, List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userPrompt)
        ), maxTokens);

        LlmResponse response = client.complete(request);
        String content = response == null ? null : response.content();
        return parse(content);
    }

    private String resolveSystemPrompt(HarnessProfile profile) {
        String override = profile.selfVerify().promptOverride();
        if (override != null && !override.isBlank()) return override;
        List<String> tiers = profile.selfVerify().escalateTiers();
        return (tiers != null && !tiers.isEmpty()) ? SYSTEM_PROMPT_WITH_ESCALATE : SYSTEM_PROMPT;
    }

    private String buildPrompt(Action action, ActionClassification classification, RunContext ctx) {
        UUID runId = ctx != null ? ctx.getRunId() : null;
        UUID agentId = ctx != null ? ctx.getAgentId() : null;
        int iteration = ctx != null ? ctx.getIterationCount() : 0;
        return String.format("""
                Action name: %s
                Action type: %s
                Risk level: %s
                Risk tier: %s
                Category: %s
                Arguments: %s
                Run id: %s
                Agent id: %s
                Iteration: %d

                Is this action safe to execute? Respond with PASS/FAIL/WARN(/ESCALATE) and a reason.
                """,
                action.name(),
                action.type(),
                classification.riskLevel(),
                safeTier(action),
                classification.category(),
                truncate(action.arguments(), 1500),
                runId, agentId, iteration);
    }

    /**
     * Parse the LLM reply. Robust to whitespace and case. Defaults to PASS if
     * the response is blank or malformed (fire-and-forget contract).
     */
    AiVerificationResult parse(String content) {
        if (content == null || content.isBlank()) {
            return AiVerificationResult.pass("Empty LLM response");
        }
        String trimmed = content.strip();
        String firstLine = trimmed.split("\\R", 2)[0].trim().toUpperCase();
        String reasoning = trimmed.contains("\n")
                ? trimmed.substring(trimmed.indexOf('\n') + 1).trim()
                : trimmed;

        if (firstLine.startsWith("FAIL")) {
            return AiVerificationResult.fail(reasoning, 0.9);
        }
        if (firstLine.startsWith("ESCALATE") || firstLine.startsWith("NEEDS_HITL")
                || firstLine.startsWith("NEEDS HITL")) {
            return AiVerificationResult.escalate(reasoning, 0.8);
        }
        if (firstLine.startsWith("WARN")) {
            return AiVerificationResult.warn(reasoning, 0.7);
        }
        if (firstLine.startsWith("PASS")) {
            return AiVerificationResult.pass(reasoning);
        }
        // Unrecognised format — keep the pipeline moving.
        log.debug("AI verification response did not start with PASS/FAIL/WARN/ESCALATE, defaulting to PASS: {}",
                truncate(content, 200));
        return AiVerificationResult.pass("Unparsed: " + truncate(reasoning, 200));
    }

    /** Resolve the action's governance risk tier defensively (READ on any error). */
    private String safeTier(Action action) {
        try {
            return riskResolver.resolve(action.name()).name();
        } catch (Exception e) {
            return "READ";
        }
    }

    private String buildCacheKey(RunContext ctx, Action action) {
        UUID runId = ctx != null ? ctx.getRunId() : null;
        int iteration = ctx != null ? ctx.getIterationCount() : 0;
        return runId + ":" + iteration + ":" + action.id();
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
