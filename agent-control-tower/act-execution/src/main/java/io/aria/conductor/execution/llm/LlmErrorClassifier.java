package io.aria.conductor.execution.llm;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

public final class LlmErrorClassifier {

    public enum LlmErrorClass { TRANSIENT, PERMANENT, UNKNOWN }

    private LlmErrorClassifier() {}

    /**
     * Classify an LLM error into transient (retryable), permanent (fail fast),
     * or unknown (retry with caution).
     */
    public static LlmErrorClass classify(Throwable error, int httpStatus) {
        // Transient HTTP statuses
        if (httpStatus == 429 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
            return LlmErrorClass.TRANSIENT;
        }
        // Permanent HTTP statuses
        if (httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 422) {
            return LlmErrorClass.PERMANENT;
        }
        // Transient exceptions (network-level)
        if (error instanceof HttpTimeoutException || error instanceof ConnectException) {
            return LlmErrorClass.TRANSIENT;
        }
        // Transient exceptions by cause chain
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException || cause instanceof ConnectException) {
                return LlmErrorClass.TRANSIENT;
            }
            cause = cause.getCause();
        }
        // Known server errors (500) → unknown
        if (httpStatus >= 500) {
            return LlmErrorClass.UNKNOWN;
        }
        return LlmErrorClass.UNKNOWN;
    }
}
