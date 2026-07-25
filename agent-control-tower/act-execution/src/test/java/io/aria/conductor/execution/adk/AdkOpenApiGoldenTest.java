package io.aria.conductor.execution.adk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmProperties;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.llm.LlmToolCall;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for the Java &lt;-&gt; Python ADK boundary, driven by the committed
 * OpenAPI golden schema of the FastAPI ADK server.
 *
 * <p><b>Provenance:</b> the source of truth is {@code langchain-adk/openapi.golden.json}
 * (regenerated from {@code src.server.app.openapi()}); the copy under
 * {@code src/test/resources/adk/openapi.golden.json} is kept in sync manually and must be
 * byte-identical. Drift is caught on the Python side by
 * {@code langchain-adk/tests/test_openapi_golden.py} (FastAPI schema == golden) and on the
 * Java side by this test's endpoint/schema assertions against the same golden.
 *
 * <p>Assertion style is <i>tolerant reader</i>: only the endpoints and fields the Java
 * consumer ({@link LangChainAdkProvider}, {@link AdkHttpClient}) actually uses are asserted,
 * so unrelated server-side additions never break this test.
 *
 * <p><b>Known drift (documented, not hidden, and deliberately not build-failing because the
 * drifted code path has no production callers):</b>
 * <ul>
 *   <li>{@link AdkHttpClient#shutdown} targets {@code POST /shutdown}, which the Python ADK
 *       does not expose (golden paths are only /health, /run, /status/{agent_id}).
 *       {@code AdkHttpClient} is currently referenced only by its own unit test — the live
 *       production path is {@link LangChainAdkProvider}, which spawns/destroys the subprocess
 *       directly and never calls /shutdown.</li>
 *   <li>{@link AdkRunRequest} serializes camelCase {@code agentId}/{@code sessionId}/{@code input},
 *       none of which exist in the golden {@code RunRequest} schema (snake_case
 *       {@code agent_id}/{@code session_id}/{@code messages}). Only its {@code context} field is
 *       schema-consistent. Consequently, request-body subset assertions are made against the
 *       production serializer ({@code LangChainAdkProvider#buildRequestBody}) only; the
 *       {@code AdkHttpClient} WireMock leg asserts response deserialization round-trip only.</li>
 *   <li>The golden declares the {@code /run} 200 response schema as an empty (opaque) object —
 *       it is an SSE stream FastAPI cannot describe. Response-shape agreement is therefore
 *       proven by the WireMock round-trip legs instead of schema introspection.</li>
 * </ul>
 */
@WireMockTest
class AdkOpenApiGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode golden;

    @BeforeAll
    static void loadGolden() throws Exception {
        try (InputStream in = AdkOpenApiGoldenTest.class.getResourceAsStream("/adk/openapi.golden.json")) {
            assertThat(in).as("golden schema resource /adk/openapi.golden.json").isNotNull();
            golden = MAPPER.readTree(in);
        }
    }

    private static JsonNode schema(String name) {
        JsonNode node = golden.path("components").path("schemas").path(name);
        assertThat(node.isObject()).as("components.schemas.%s present in golden", name).isTrue();
        return node;
    }

    private static List<String> fieldNames(JsonNode objectNode) {
        List<String> names = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- Leg 1: golden schema introspection (pure Jackson, no I/O) ----

    @Test
    void goldenDeclaresEveryEndpointTheProductionClientCalls() {
        JsonNode paths = golden.path("paths");

        // LangChainAdkProvider (production path): POST /run + GET /health.
        assertThat(paths.path("/run").has("post"))
                .as("POST /run declared in golden").isTrue();
        assertThat(paths.path("/health").has("get"))
                .as("GET /health declared in golden").isTrue();

        // Drift tripwire: AdkHttpClient.shutdown() calls POST /shutdown, which the ADK
        // does not expose (see class javadoc). If the server ever adds /shutdown, this
        // assertion fails on purpose so the contract here gets tightened.
        assertThat(paths.has("/shutdown"))
                .as("/shutdown is a documented drift — absent from golden").isFalse();

        // Golden path inventory, pinned so any server-surface change is reviewed here.
        assertThat(fieldNames(paths))
                .containsExactlyInAnyOrder("/health", "/run", "/status/{agent_id}");
    }

    @Test
    void runEndpointDeclaresJsonRequestBoundToRunRequestSchema() {
        JsonNode post = golden.path("paths").path("/run").path("post");

        JsonNode requestBody = post.path("requestBody");
        assertThat(requestBody.path("required").asBoolean()).isTrue();
        assertThat(requestBody.path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/RunRequest");

        // 200 exists but is an opaque SSE stream (empty schema object) — response shape
        // is proven by the WireMock round-trip legs below.
        JsonNode ok = post.path("responses").path("200");
        assertThat(ok.path("description").asText()).isEqualTo("Successful Response");
        assertThat(ok.path("content").path("application/json").path("schema").size()).isZero();
    }

    @Test
    void runRequestSchemaCoversEveryFieldTheProviderSends() {
        JsonNode runRequest = schema("RunRequest");
        JsonNode props = runRequest.path("properties");

        // Exact top-level field set written by LangChainAdkProvider#buildRequestBody.
        List<String> javaSent = List.of("agent_id", "session_id", "model", "llm_api_key",
                "llm_base_url", "max_tokens", "messages", "tools", "context");
        assertThat(fieldNames(props)).containsAll(javaSent);

        // Value-level type agreement for the fields Java populates.
        assertThat(props.path("agent_id").path("type").asText()).isEqualTo("string");
        assertThat(props.path("session_id").path("type").asText()).isEqualTo("string");
        assertThat(props.path("model").path("type").asText()).isEqualTo("string");
        assertThat(props.path("max_tokens").path("type").asText()).isEqualTo("integer");
        assertThat(props.path("messages").path("type").asText()).isEqualTo("array");
        assertThat(props.path("messages").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/Message");
        assertThat(props.path("tools").path("type").asText()).isEqualTo("array");
        assertThat(props.path("context").path("$ref").asText())
                .isEqualTo("#/components/schemas/Context");

        // llm_api_key / llm_base_url are nullable strings (anyOf string|null).
        for (String nullable : List.of("llm_api_key", "llm_base_url")) {
            List<String> anyOfTypes = new ArrayList<>();
            props.path(nullable).path("anyOf").forEach(t -> anyOfTypes.add(t.path("type").asText()));
            assertThat(anyOfTypes).as("%s anyOf types", nullable)
                    .containsExactlyInAnyOrder("string", "null");
        }

        // The only server-required field is agent_id — and Java always sends it.
        List<String> required = new ArrayList<>();
        runRequest.path("required").forEach(r -> required.add(r.asText()));
        assertThat(required).containsExactly("agent_id");
    }

    @Test
    void messageAndToolCallSchemasCoverFieldsTheProviderSends() {
        JsonNode message = schema("Message");
        // Per-message fields written by buildRequestBody.
        assertThat(fieldNames(message.path("properties")))
                .containsAll(List.of("role", "content", "tool_call_id", "tool_calls"));
        assertThat(message.path("properties").path("role").path("type").asText()).isEqualTo("string");
        List<String> messageRequired = new ArrayList<>();
        message.path("required").forEach(r -> messageRequired.add(r.asText()));
        assertThat(messageRequired).containsExactly("role"); // Java always writes role

        JsonNode toolCall = schema("ToolCall");
        // Java writes id/name/arguments for assistant tool_calls — all three required server-side.
        assertThat(fieldNames(toolCall.path("properties")))
                .containsExactlyInAnyOrder("id", "name", "arguments");
        List<String> toolCallRequired = new ArrayList<>();
        toolCall.path("required").forEach(r -> toolCallRequired.add(r.asText()));
        assertThat(toolCallRequired).containsExactlyInAnyOrder("id", "name", "arguments");
        assertThat(toolCall.path("properties").path("arguments").path("type").asText())
                .isEqualTo("string"); // Java sends arguments as a JSON string, not an object
    }

    @Test
    void healthEndpointShapeMatchesJavaHealthProbe() {
        // Both Java health checks (AdkHttpClient#checkHealth and the provider's private
        // checkHealth) parse nothing from the body — a 2xx status is the whole contract.
        JsonNode get = golden.path("paths").path("/health").path("get");
        assertThat(get.path("responses").has("200")).isTrue();
        assertThat(get.path("responses").path("200").path("content").has("application/json")).isTrue();
    }

    // ---- Leg 2: WireMock round-trips with golden-shaped stubs (zero live Python) ----

    @Test
    void providerRun_roundTrips_andRequestBodyIsSubsetOfGoldenRunRequestSchema(WireMockRuntimeInfo wm) {
        LangChainAdkProperties properties = new LangChainAdkProperties();
        properties.setHost("127.0.0.1");
        properties.setPortRangeStart(9300);
        properties.setPortRangeEnd(9301);
        properties.setLlmBaseUrl("https://api.deepseek.com/v1");
        properties.setLlmDefaultModel("deepseek-chat");
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setMaxTokens(1000);
        LlmProviderRepository providerRepository = mock(LlmProviderRepository.class);
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        LangChainAdkProvider provider = new LangChainAdkProvider(
                properties, llmProperties, mock(AdkProcessReaper.class), providerRepository);

        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId,
                new AdkInstance(agentId, wm.getHttpPort(), null, Instant.now(), Instant.now(), true, 0));

        // SSE response event uses the field names the provider parses; the nested tool_call
        // uses exactly the golden ToolCall property names (id/name/arguments).
        stubFor(post(urlEqualTo("/run")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("event: response\n"
                        + "data: {\"content\":\"golden roundtrip\",\"finish_reason\":\"tool_calls\","
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3},"
                        + "\"tool_calls\":[{\"id\":\"call-1\",\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"adk\\\"}\"}]}\n")));

        LlmResponse response = provider.call(agentId,
                List.of(LlmMessage.user("ping"),
                        LlmMessage.assistant(null, List.of(new LlmToolCall("call-0", "lookup", "{}"))),
                        LlmMessage.tool("lookup-result", "call-0")),
                List.of(Map.of("type", "function", "function", Map.of("name", "search"))));

        assertThat(response.content()).isEqualTo("golden roundtrip");
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.inputTokens()).isEqualTo(7);
        assertThat(response.outputTokens()).isEqualTo(3);
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(response.toolCalls().get(0).name()).isEqualTo("search");
        assertThat(response.toolCalls().get(0).arguments()).isEqualTo("{\"q\":\"adk\"}");

        // Tolerant-reader inversion: every field the real provider PUT ON THE WIRE must be
        // known to the golden RunRequest/Message/ToolCall schemas.
        var recorded = findAll(postRequestedFor(urlEqualTo("/run")));
        assertThat(recorded).hasSize(1);
        JsonNode sent = readJson(recorded.get(0).getBodyAsString());
        List<String> goldenRunRequestFields = fieldNames(schema("RunRequest").path("properties"));
        assertThat(fieldNames(sent)).isSubsetOf(goldenRunRequestFields);
        assertThat(sent.path("agent_id").asText()).isEqualTo(agentId.toString());
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1000);
        assertThat(sent.path("messages").size()).isEqualTo(3);
        List<String> goldenMessageFields = fieldNames(schema("Message").path("properties"));
        List<String> goldenToolCallFields = fieldNames(schema("ToolCall").path("properties"));
        for (JsonNode msg : sent.path("messages")) {
            assertThat(fieldNames(msg)).isSubsetOf(goldenMessageFields);
            assertThat(msg.path("role").isTextual()).as("required Message.role sent").isTrue();
            for (JsonNode tc : msg.path("tool_calls")) {
                assertThat(fieldNames(tc)).containsExactlyInAnyOrderElementsOf(goldenToolCallFields);
            }
        }
    }

    @Test
    void adkHttpClient_runAndHealth_roundTripAgainstGoldenShapedStubs(WireMockRuntimeInfo wm) throws Exception {
        AdkHttpClient client = new AdkHttpClient();

        // /health body is exactly what the FastAPI handler returns ({"status":"ok"});
        // the golden 200 schema is unconstrained and Java only reads the status code.
        stubFor(get(urlEqualTo("/health")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"ok\"}")));
        assertThat(client.checkHealth("127.0.0.1", wm.getHttpPort()))
                .isEqualTo(AdkHealthStatus.HEALTHY);

        // SSE events keyed by session_id (golden RunRequest field name echoed back) prove
        // AdkRunResponse deserialization agrees with the golden naming convention.
        stubFor(post(urlEqualTo("/run")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: {\"session_id\":\"sess-golden-1\",\"output\":\"hello \"}\n"
                        + "\n"
                        + "data: {\"output\":\"world\"}\n"
                        + "\n"
                        + "data: [DONE]\n")));

        AdkRunResponse resp = client
                .submitRun("127.0.0.1", wm.getHttpPort(), AdkRunRequest.of("agent-1", "ping"))
                .get(10, TimeUnit.SECONDS);

        assertThat(resp.success()).isTrue();
        assertThat(resp.errorMessage()).isNull();
        assertThat(resp.sessionId()).isEqualTo("sess-golden-1");
        assertThat(resp.output()).isEqualTo("hello world");
        assertThat(resp.events()).containsExactly(
                "{\"session_id\":\"sess-golden-1\",\"output\":\"hello \"}",
                "{\"output\":\"world\"}");
    }

    private static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Recorded /run request body is not valid JSON: " + body, e);
        }
    }
}
