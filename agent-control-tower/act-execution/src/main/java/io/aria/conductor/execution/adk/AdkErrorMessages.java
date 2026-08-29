package io.aria.conductor.execution.adk;

/**
 * Translates raw ADK/provider error text into actionable, user-facing messages.
 *
 * <p>F6: the chat UI previously displayed verbatim provider exceptions (e.g. the
 * LangChain "Missing credentials … OPENAI_API_KEY …" dump). Known failure classes
 * are mapped to friendly hints here; unknown errors pass through unchanged and the
 * raw detail stays in server logs / the exception cause chain.
 */
public final class AdkErrorMessages {

    private AdkErrorMessages() {
    }

    public static String friendly(String raw) {
        if (raw == null || raw.isBlank()) {
            return "The agent runtime failed unexpectedly. Please try again.";
        }
        String lower = raw.toLowerCase();
        if (lower.contains("missing credentials") || lower.contains("api_key")
                || lower.contains("incorrect api key") || lower.contains("authentication")) {
            return "No LLM provider credentials are configured for this agent. "
                    + "Add an API key on the Providers page (or set LLM_API_KEY) and retry.";
        }
        if (lower.contains("connection refused") || lower.contains("unreachable")
                || lower.contains("connection reset")) {
            return "The agent runtime is unreachable. It may still be starting — try again shortly.";
        }
        return raw;
    }
}
