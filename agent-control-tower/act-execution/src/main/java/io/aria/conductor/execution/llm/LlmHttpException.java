package io.aria.conductor.execution.llm;

public class LlmHttpException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public LlmHttpException(int statusCode, String responseBody) {
        super("LLM request failed with HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
}
