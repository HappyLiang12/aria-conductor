package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void error_withDebug_includesFullStack() throws Exception {
        RuntimeException boom = new IllegalStateException("state broke");
        String json = ToolResponses.error("CONFLICT", "state broke", boom, true);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("stackTrace").asText())
                .contains("java.lang.IllegalStateException: state broke")
                .contains("ToolResponsesTest.error_withDebug_includesFullStack");
    }

    @Test
    void error_escapesQuotesInMessage() throws Exception {
        String json = ToolResponses.error("VALIDATION", "field \"name\" blank", null, false);
        assertThat(mapper.readTree(json).get("message").asText()).isEqualTo("field \"name\" blank");
    }
}
