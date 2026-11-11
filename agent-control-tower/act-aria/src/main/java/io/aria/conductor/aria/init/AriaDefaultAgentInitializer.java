package io.aria.conductor.aria.init;

import io.aria.conductor.common.model.*;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.maintenance.LegacySetupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Ensures the Aria default agent exists at startup.
 * Idempotent — safe to run on every startup.
 *
 * <p>Task 18 cutover: the Aria row is created through
 * {@link LegacySetupService#initializeMissingAria(String)} -- create-only, on the
 * supported {@code opencode} core in {@code SANDBOX} mode -- and is never
 * re-pointed, edited or pre-warmed. The legacy-provider repointing block and the
 * permanent ADK pre-warm (with its DEGRADED reconciler) are gone with the
 * LangChain runtime: the run cutover executes Aria through the run coordinator,
 * which has no per-agent warm-up step.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AriaDefaultAgentInitializer implements ApplicationRunner {

    private final LlmProviderRepository llmProviderRepository;
    private final LegacySetupService legacySetupService;

    public AriaDefaultAgentInitializer(LlmProviderRepository llmProviderRepository,
                                       LegacySetupService legacySetupService) {
        this.llmProviderRepository = llmProviderRepository;
        this.legacySetupService = legacySetupService;
    }

    private String buildAriaConfig() {
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("maxToolCallRounds", 15);
            // Aria is the interactive operator assistant: the human just sent the chat message,
            // so a separate task-level approval gate before every LLM call is redundant and would
            // block the SSE stream indefinitely (the operator is already present). Opt out.
            cfg.put("taskApprovalRequired", false);
            // P1: the managed prompt lives in AriaPromptContributor. The stored row only marks
            // that this agent's prompt is managed and versioned; it carries no prompt text.
            cfg.put("managedPromptVersion", 1);
            return new ObjectMapper().writeValueAsString(cfg);
        } catch (Exception e) {
            log.warn("Failed to build Aria config, using fallback", e);
            return "{\"maxToolCallRounds\":15}";
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing Aria default agent...");

        // Boot hardening: both steps run inside the HIGHEST_PRECEDENCE ApplicationRunner —
        // an exception there used to abort the whole JVM before any other runner, and the
        // DEGRADED reconciler could never retry because the app was already dead. Degrade
        // instead: every step is idempotent and retried on the next boot, so log ERROR
        // and continue (pre-warm is skipped when the Aria row could not be loaded/created).
        Agent aria = null;
        try {
            // 1. Ensure the Aria agent exists. The managed write (name/role/provider/config)
            //    is CREATE-only: an existing record is left untouched so operator edits —
            //    notably taskApprovalRequired in config — survive restarts. The row is
            //    created by LegacySetupService (create-only), on opencode + SANDBOX.
            aria = legacySetupService.initializeMissingAria(buildAriaConfig());
            if (aria != null) {
                log.info("Aria agent ready (id={}); existing rows are left untouched",
                        AriaConstants.ARIA_AGENT_ID);
            }

            // 2. Ensure at least one LLM provider is active (generic, provider-agnostic)
            if (llmProviderRepository.findByActiveTrue().isEmpty()) {
                String apiKey = System.getenv("LLM_API_KEY");
                String baseUrl = System.getenv("LLM_BASE_URL");
                String model = System.getenv("LLM_MODEL");
                if (apiKey != null && !apiKey.isBlank()) {
                    String name = inferProviderName(baseUrl);
                    LlmProviderType type = inferProviderType(baseUrl);
                    LlmProvider provider = LlmProvider.builder()
                            .name(name)
                            .type(type)
                            .baseUrl(baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                            .apiKey(apiKey)
                            .defaultModel(model != null ? model : "gpt-4o")
                            .defaultMaxTokens(4096)
                            .active(true)
                            .build();
                    llmProviderRepository.save(provider);
                    log.info("Auto-created and ACTIVATED {} LLM provider from environment (LLM_API_KEY set).", name);
                } else {
                    // Escalated from INFO (degraded-silent finding #8): with no env key AND no
                    // DB provider, the opencode config write falls back to hardcoded deepseek
                    // defaults — a fresh install would silently target a provider the operator
                    // never configured (wrong model/keys at first run).
                    log.warn("LLM provider not configured — falling back to deepseek defaults; set LLM_API_KEY or create a provider "
                            + "(POST /api/v1/llm-providers, or set LLM_BASE_URL/LLM_MODEL to override the deepseek fallback).");
                }
            }
        } catch (Exception e) {
            log.error("Aria initialization (agent setup / LLM bootstrap) failed — "
                            + "continuing startup in degraded state; every step is idempotent and retried on the next boot. Cause: {}",
                    e.getMessage(), e);
        }
    }

    private static String inferProviderName(String baseUrl) {
        if (baseUrl == null) return "Default";
        if (baseUrl.contains("deepseek")) return "DeepSeek";
        if (baseUrl.contains("openai")) return "OpenAI";
        if (baseUrl.contains("anthropic")) return "Anthropic";
        return "Custom";
    }

    private static LlmProviderType inferProviderType(String baseUrl) {
        return LlmProviderType.OPENAI;
    }
}
