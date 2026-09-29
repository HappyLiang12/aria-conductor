package io.aria.conductor.app.e2e;

import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic, fixture-only LLM client of the core E2E harness (Task 16).
 *
 * <p>It is wired as both {@code rawLlmClient} and the primary
 * {@code resilientLlmClient} by {@link CoreE2eConfiguration}, so the
 * harness application has exactly one LLM implementation and no path to a
 * network client: the production {@code DefaultLlmClient} and its retry
 * decorator are replaced, not decorated.
 *
 * <p>Contract:
 * <ul>
 *   <li><b>records</b> every request it sees, matched or not, so a test can
 *       assert the exact conversation a run produced;</li>
 *   <li><b>matches scenario responses exactly on the whole request value</b>:
 *       the key is the {@link LlmRequest} itself, compared with the record's own
 *       structural equality -- model, {@code maxTokens}, {@code temperature},
 *       the {@code tools} definitions, and every field of every message
 *       ({@code role}, {@code content}, {@code toolCallId}, {@code toolCalls}).
 *       Identity is never a substring, prefix, regex or a projection onto the
 *       message text, so a conversation that changes by one tool-call
 *       correlation id, one tool definition or one token limit fails instead of
 *       silently reusing a stale response;</li>
 *   <li><b>fails loudly on unmatched input</b> with an
 *       {@link IllegalStateException} that names the unmatched request -- it
 *       never falls back to a canned default, and therefore never hides
 *       "unexpected external traffic" behind a plausible-looking answer. The
 *       production HTTP clients are not even constructed.</li>
 * </ul>
 *
 * <p>Registrations are added programmatically (a harness test or a later
 * harness task registers the exact prompts of its scenario). An empty client
 * fails on every call, which is the intended state until a scenario is
 * selected: the harness must never invent model output.
 *
 * <p>What the key does not distinguish, disclosed:
 * <ul>
 *   <li>{@code tools} entries compare with {@code Map.equals}: the same tool
 *       definition written with a different entry order is the same request;</li>
 *   <li>values inside {@code tools} maps compare with {@code Object.equals} --
 *       strings, numbers, booleans, nested maps and lists by content, any other
 *       (array, mutable) object by identity;</li>
 *   <li>record equality compares the {@code double} component by bits, so
 *       {@code -0.0} and {@code 0.0} are distinct and {@code NaN} equals
 *       {@code NaN};</li>
 *   <li>the key is the live request value: a registered request must not be
 *       mutated afterwards (build the exact request, register it, leave its
 *       lists alone) or it becomes unfindable.</li>
 * </ul>
 */
public final class DeterministicLlmClient implements LlmClient {

    private final ConcurrentMap<LlmRequest, LlmResponse> scenarioResponses = new ConcurrentHashMap<>();
    private final List<LlmRequest> recordedRequests = new CopyOnWriteArrayList<>();

    /**
     * Registers one exact request/response pair; re-registering the same exact
     * request value replaces it. The key is the whole request value (see the
     * class javadoc for the exact identity and its disclosed limits).
     */
    public void registerExact(LlmRequest request, LlmResponse response) {
        scenarioResponses.put(request, Objects.requireNonNull(response, "response"));
    }

    /** Registers an exact request with a plain text completion. */
    public void registerExact(LlmRequest request, String responseContent) {
        registerExact(request, new LlmResponse(responseContent, 0, 0, "stop", List.of()));
    }

    /**
     * Registers a response for the exact single-user-message request shape
     * ({@code LlmRequest.of(model, [user prompt], 4096)}: the record default
     * temperature of 0.7 and no tools are part of that identity like any other
     * request field).
     */
    public void registerExact(String model, String userPrompt, String responseContent) {
        registerExact(LlmRequest.of(model, List.of(LlmMessage.user(userPrompt)), 4096), responseContent);
    }

    /** Every request observed, in arrival order, matched or not. */
    public List<LlmRequest> recordedRequests() {
        return List.copyOf(recordedRequests);
    }

    /** Number of requests observed, matched or not. */
    public int callCount() {
        return recordedRequests.size();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        recordedRequests.add(request);
        LlmResponse response = scenarioResponses.get(request);
        if (response == null) {
            throw new IllegalStateException("DeterministicLlmClient: no exact scenario response is"
                    + " registered for this request, and the harness never invents model output or"
                    + " reaches an external provider. Unmatched request: " + render(request));
        }
        return response;
    }

    @Override
    public Flux<String> stream(LlmRequest request) {
        // Eager: an unmatched stream fails at call time with the same exact
        // diagnostic as complete(), never as an empty or partial stream.
        LlmResponse response = complete(request);
        return Flux.just(response.content());
    }

    /**
     * A readable rendering of an unmatched request naming every field the match
     * key covers: model, token limit, temperature, the tool definitions and,
     * per message, role, content, tool-call correlation id and tool calls.
     */
    private static String render(LlmRequest request) {
        StringBuilder out = new StringBuilder("model=").append(request.model())
                .append(" maxTokens=").append(request.maxTokens())
                .append(" temperature=").append(request.temperature())
                .append(" tools=").append(request.tools());
        for (LlmMessage message : request.messages()) {
            out.append("\n--- ").append(message.role())
                    .append(" content=").append(message.content())
                    .append(" toolCallId=").append(message.toolCallId())
                    .append(" toolCalls=").append(message.toolCalls());
        }
        return out.toString();
    }
}
