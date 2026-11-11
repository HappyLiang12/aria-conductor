package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResponsesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ok_wrapsPayload() throws Exception {
        String json = ToolResponses.ok(new java.util.LinkedHashMap<>(java.util.Map.of("id", "c1")));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("ok").asBoolean()).isTrue();
        assertThat(node.get("data").get("id").asText()).isEqualTo("c1");
    }

    @Test
    void error_withoutDebug_hasNoStackTrace() throws Exception {
        String json = ToolResponses.error("NOT_FOUND", "KnowledgeItem missing", new RuntimeException("boom"), false);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("ok").asBoolean()).isFalse();
        assertThat(node.get("errorType").asText()).isEqualTo("NOT_FOUND");
        assertThat(node.get("message").asText()).isEqualTo("KnowledgeItem missing");
        assertThat(node.has("stackTrace")).isFalse();
    }

    @Test
    void error_withDebug_stillNeverSerializesTheStack() throws Exception {
        // Fix round 7: the client answer never carries the exception class or a
        // stack trace (information disclosure on an operator-facing tool
        // response). debug=true only records the cause server-side, so the
        // envelope stays exactly ok/errorType/message even with debug on.
        RuntimeException boom = new IllegalStateException("state broke");
        String json = ToolResponses.error("CONFLICT", "state broke", boom, true);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("ok").asBoolean()).isFalse();
        assertThat(node.get("errorType").asText()).isEqualTo("CONFLICT");
        assertThat(node.get("message").asText()).isEqualTo("state broke");
        assertThat(node.has("stackTrace")).isFalse();
        assertThat(node.size()).isEqualTo(3);
        assertThat(json).doesNotContain("IllegalStateException");
    }

    @Test
    void error_escapesQuotesInMessage() throws Exception {
        String json = ToolResponses.error("VALIDATION", "field \"name\" blank", null, false);
        assertThat(mapper.readTree(json).get("message").asText()).isEqualTo("field \"name\" blank");
    }

    /**
     * The one refusal shape every operator-only tool speaks (spec C4/F9): a stable code, the
     * named action and the named surface where the operator can actually do it. A bare
     * "forbidden" is the defect this contract exists to prevent.
     */
    @Test
    void operatorOnly_namesTheActionAndTheSurface() {
        assertThat(ToolResponses.operatorOnly("retiring an agent", "Retire it on the Crew page."))
                .isEqualTo("Operator authority required: retiring an agent is an operator decision. "
                        + "Retire it on the Crew page.");
    }

    @Test
    void requireOperator_refusesAWorkerWithTheActionableRefusal() {
        ActorPrincipal worker = ActorPrincipal.worker(UUID.randomUUID(), Instant.parse("2026-11-09T12:10:00Z"));

        assertThatThrownBy(() -> ToolResponses.requireOperator(worker, "retiring an agent", "Retire it on the Crew page."))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator authority required: retiring an agent is an operator decision. "
                        + "Retire it on the Crew page.");
    }

    @Test
    void requireOperator_refusesAnAbsentActor() {
        assertThatThrownBy(() -> ToolResponses.requireOperator(null, "retiring an agent", "Retire it on the Crew page."))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Operator authority required: retiring an agent");
    }

    @Test
    void requireOperator_acceptsTheOperatorCredential() {
        assertThatCode(() -> ToolResponses.requireOperator(ActorPrincipal.operator(null),
                "retiring an agent", "Retire it on the Crew page."))
                .doesNotThrowAnyException();
    }

    @Test
    void ok_serializesInstantFields_viaRegisteredJavaTimeModule() throws Exception {
        // Regression: a bare ObjectMapper throws InvalidDefinitionException on
        // java.time.Instant — every real DTO (WorkflowResponse etc.) is Instant-bearing
        // and would silently degrade to the SERIALIZATION fallback envelope.
        String json = ToolResponses.ok(java.util.Map.of("createdAt", java.time.Instant.parse("2026-09-05T00:00:00Z")));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("data").get("createdAt").asText()).isEqualTo("2026-09-05T00:00:00Z");
    }
}
