package io.aria.conductor.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.execution.llm.LlmToolCall;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F property-based tests for {@link AgentLoopEngine#parseTrajectoryToolCalls}
 * (package-private static, reachable from this same-package test without any
 * production change).
 *
 * <p>Total-function property: the parser never throws, whatever garbage it is fed —
 * it degrades to an empty list. Extraction property: for well-formed tool-call
 * arrays built independently with Jackson (not the production serializer), the
 * parser extracts exactly the injected ids/names/arguments, in order.
 */
class AgentLoopEngineTrajectoryPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── never throws on arbitrary input ──────────────────────────────────

    @Property(tries = 500)
    void parserNeverThrowsOnArbitraryStrings(@ForAll("arbitraryJunk") String input) {
        List<LlmToolCall> result = AgentLoopEngine.parseTrajectoryToolCalls(input);

        // Total function: always a non-null list, garbage degrades to empty.
        assertThat(result).isNotNull();
    }

    @Property(tries = 200)
    void parserReturnsEmptyForNonArrayJson(@ForAll("arbitraryJunk") String input) {
        boolean parsesAsArray;
        try {
            parsesAsArray = MAPPER.readTree(input).isArray();
        } catch (Exception e) {
            parsesAsArray = false;
        }
        if (!parsesAsArray) {
            assertThat(AgentLoopEngine.parseTrajectoryToolCalls(input)).isEmpty();
        }
    }

    // ── well-formed snippets: extracts exactly the injected tool calls ───

    @Property(tries = 300)
    void parserExtractsExactlyTheInjectedToolCallsInOrder(
            @ForAll("toolCallBatches") List<LlmToolCall> injected) throws Exception {
        ArrayNode array = MAPPER.createArrayNode();
        for (LlmToolCall call : injected) {
            ObjectNode node = array.addObject();
            node.put("id", call.id());
            node.put("name", call.name());
            node.put("arguments", call.arguments());
        }
        String json = MAPPER.writeValueAsString(array);

        List<LlmToolCall> parsed = AgentLoopEngine.parseTrajectoryToolCalls(json);

        assertThat(parsed).hasSize(injected.size());
        for (int i = 0; i < injected.size(); i++) {
            assertThat(parsed.get(i).id()).isEqualTo(injected.get(i).id());
            assertThat(parsed.get(i).name()).isEqualTo(injected.get(i).name());
            assertThat(parsed.get(i).arguments()).isEqualTo(injected.get(i).arguments());
        }
        assertThat(parsed).map(LlmToolCall::name)
                .containsExactlyElementsOf(injected.stream().map(LlmToolCall::name).toList());
    }

    @Property(tries = 200)
    void productionSerializerRoundTripsThroughTheParser(@ForAll("toolCallBatches") List<LlmToolCall> injected) {
        String json = AgentLoopEngine.buildToolCallsJson(injected);

        assertThat(AgentLoopEngine.parseTrajectoryToolCalls(json)).isEqualTo(injected);
    }

    // ---- generators ----

    @Provide
    Arbitrary<String> arbitraryJunk() {
        // Full printable BMP plus JSON-ish punctuation; deliberately includes strings
        // that look almost like JSON so the parser's error paths are exercised.
        Arbitrary<String> anyChars = Arbitraries.strings()
                .withCharRange('\u0020', '\ud7ff')
                .withChars('{', '}', '[', ']', '"', ':', ',', '\\', '\n', '\t')
                .ofMaxLength(200);
        Arbitrary<String> jsonish = Arbitraries.of(
                "[", "]", "[{", "[{}]", "[{\"id\":}]", "{\"not\":\"array\"}",
                "null", "true", "123", "[1,2,3]", "[\"just\",\"strings\"]");
        return Arbitraries.oneOf(anyChars, jsonish);
    }

    @Provide
    Arbitrary<List<LlmToolCall>> toolCallBatches() {
        Arbitrary<String> ids = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> names = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('_').ofMinLength(1).ofMaxLength(30);
        Arbitrary<String> args = Arbitraries.strings()
                .withCharRange('\u0020', '\ud7ff')
                .withChars('"', '\\', '\n', '\t', '{', '}')
                .ofMaxLength(120);
        Arbitrary<LlmToolCall> calls = net.jqwik.api.Combinators.combine(ids, names, args)
                .as(LlmToolCall::new);
        return calls.list().ofMinSize(0).ofMaxSize(8);
    }
}
