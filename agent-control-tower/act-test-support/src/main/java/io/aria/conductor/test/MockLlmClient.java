package io.aria.conductor.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Deterministic LLM client substitute for unit tests.
 * <p>
 * Register prompt patterns to canned responses, then assert the recorded
 * call sequence. All patterns are matched as regular expressions; the first
 * matching pattern in insertion order wins. If no pattern matches the
 * configured default response is returned.
 */
public class MockLlmClient {

    private final Map<String, String> responses = new LinkedHashMap<>();
    private final List<String> recordedPrompts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger callCount = new AtomicInteger(0);
    private String defaultResponse = "Mock LLM response";

    /**
     * Register a regex pattern that, when matched against a prompt, will
     * return the supplied response.
     */
    public MockLlmClient respondWith(String pattern, String response) {
        responses.put(pattern, response);
        return this;
    }

    /**
     * Set the response returned when no registered pattern matches.
     */
    public MockLlmClient withDefault(String response) {
        this.defaultResponse = response;
        return this;
    }

    /**
     * Record the prompt and return the matching canned response (or default).
     */
    public String complete(String prompt) {
        recordedPrompts.add(prompt);
        callCount.incrementAndGet();
        for (Map.Entry<String, String> entry : responses.entrySet()) {
            if (Pattern.compile(entry.getKey(), Pattern.DOTALL).matcher(prompt).find()) {
                return entry.getValue();
            }
        }
        return defaultResponse;
    }

    public int getCallCount() {
        return callCount.get();
    }

    public List<String> getRecordedPrompts() {
        synchronized (recordedPrompts) {
            return List.copyOf(recordedPrompts);
        }
    }

    /**
     * Clear recorded calls and registered responses.
     */
    public void reset() {
        responses.clear();
        recordedPrompts.clear();
        callCount.set(0);
        defaultResponse = "Mock LLM response";
    }
}
