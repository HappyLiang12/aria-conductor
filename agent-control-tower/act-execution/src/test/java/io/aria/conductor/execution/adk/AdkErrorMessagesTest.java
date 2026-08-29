package io.aria.conductor.execution.adk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F6 regression: raw provider exceptions (credential dumps, stack-ish text) must
 * be translated into actionable user-facing messages at the ADK boundary.
 */
class AdkErrorMessagesTest {

    @Test
    void missingCredentials_mapsToActionableProviderHint() {
        String raw = "LangChain /run failed for agent 00000000-0000-0000-0000-000000000001: "
                + "LangChain ADK error: Missing credentials. Please pass an api_key, workload_identity, "
                + "admin_api_key, or set the OPENAI_API_KEY or OPENAI_ADMIN_KEY environment variable.";

        String friendly = AdkErrorMessages.friendly(raw);

        assertThat(friendly)
                .contains("No LLM provider credentials")
                .contains("Providers");
        // The internal env-var dump must never reach the chat UI.
        assertThat(friendly).doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void connectionProblems_mapToRetryHint() {
        String friendly = AdkErrorMessages.friendly("java.net.ConnectException: connection refused");

        assertThat(friendly).contains("unreachable");
    }

    @Test
    void unknownErrors_passThroughUnchanged() {
        String raw = "boom: something novel happened";
        assertThat(AdkErrorMessages.friendly(raw)).isEqualTo(raw);
    }

    @Test
    void nullMessage_mapsToGenericHint() {
        assertThat(AdkErrorMessages.friendly(null)).contains("try again");
    }
}
