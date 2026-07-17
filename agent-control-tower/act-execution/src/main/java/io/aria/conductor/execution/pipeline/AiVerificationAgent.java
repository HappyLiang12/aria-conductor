package io.aria.conductor.execution.pipeline;

import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 3 of the action execution pipeline — AI safety verification agent.
 *
 * <p>Calls the configured LLM with a structured "is this action safe?" prompt
 * for HIGH_RISK / EXECUTE actions. LOW-risk READ/WRITE actions skip the call
 * entirely and return PASS.
 *
 * <p><strong>Fire-and-forget safety net</strong>: any LLM failure (no client
 * configured, network error, malformed response, …) returns PASS so the
 * pipeline never blocks on infrastructure issues. The caller can still rely
 * on Stage 2 (rules) and Stage 4 (human approval) for hard guarantees.
 *
 * <p>Per-turn batching: results for a given (runId, iterationCount, action.id())
 * tuple are cached so the same turn does not pay for repeated LLM calls when
 * an action is re-evaluated.
 */
@Slf4j
@Service
public class AiVerificationAgent {

    /** Maximum tokens reserved for the safety verification response. */
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

    private final ObjectProvider<LlmClient> llmClientProvider;
    private final Map<String, AiVerificationResult> turnCache = new ConcurrentHashMap<>();

    public AiVerificationAgent(ObjectProvider<LlmClient> llmClientProvider) {
        this.llmClientProvider = llmClientProvider;
    }

    /**
     * AI-based safety review.
     *
     * <ul>
     *     <li>LOW-classified READ/WRITE actions return PASS without calling the LLM.</li>
     *     <li>HIGH_RISK / EXECUTE / non-LOW actions invoke the LLM.</li>
     *     <li>Any LLM error returns PASS (fire-and-forget safety net).</li>
     * </ul>
     */
    public AiVerificationResult verify(Action action, ActionClassification classification, RunContext ctx) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(classification, "classification");

        if (!shouldInvokeLlm(action, classification)) {
            log.debug("AI verification skipped for low-risk action '{}'", action.name());
            return AiVerificationResult.pass("Skipped: low risk");
        }

        String cacheKey = buildCacheKey(ctx, action);
        AiVerificationResult cached = turnCache.get(cacheKey);
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
            result = callLlm(client, action, classification, ctx);
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
        turnCache.clear();
    }

    private boolean shouldInvokeLlm(Action action, ActionClassification classification) {
        // Per spec: HIGH_RISK and EXECUTE always go to AI; others only if non-LOW risk.
        if (action.type() == ActionType.HIGH_RISK || action.type() == ActionType.EXECUTE) {
            return true;
        }
        String level = classification.riskLevel();
        return level != null && !"LOW".equalsIgnoreCase(level);
    }

    private AiVerificationResult callLlm(LlmClient client, Action action,
                                         ActionClassification classification, RunContext ctx) {
        String userPrompt = buildPrompt(action, classification, ctx);
        LlmRequest request = LlmRequest.of(null, List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user(userPrompt)
        ), MAX_RESPONSE_TOKENS);

        LlmResponse response = client.complete(request);
        String content = response == null ? null : response.content();
        return parse(content);
    }

    private String buildPrompt(Action action, ActionClassification classification, RunContext ctx) {
        UUID runId = ctx != null ? ctx.getRunId() : null;
        UUID agentId = ctx != null ? ctx.getAgentId() : null;
        int iteration = ctx != null ? ctx.getIterationCount() : 0;
        return String.format("""
                Action name: %s
                Action type: %s
                Risk level: %s
                Category: %s
                Arguments: %s
                Run id: %s
                Agent id: %s
                Iteration: %d

                Is this action safe to execute? Respond with PASS/FAIL/WARN and a reason.
                """,
                action.name(),
                action.type(),
                classification.riskLevel(),
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
        if (firstLine.startsWith("WARN")) {
            return AiVerificationResult.warn(reasoning, 0.7);
        }
        if (firstLine.startsWith("PASS")) {
            return AiVerificationResult.pass(reasoning);
        }
        // Unrecognised format — keep the pipeline moving.
        log.debug("AI verification response did not start with PASS/FAIL/WARN, defaulting to PASS: {}",
                truncate(content, 200));
        return AiVerificationResult.pass("Unparsed: " + truncate(reasoning, 200));
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
