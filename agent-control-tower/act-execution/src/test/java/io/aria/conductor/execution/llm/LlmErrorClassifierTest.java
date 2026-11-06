package io.aria.conductor.execution.llm;

import org.junit.jupiter.api.Test;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorClassifierTest {

    @Test
    void shouldClassify429AsTransient() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("rate limit"), 429))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassify502AsTransient() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("bad gateway"), 502))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassify503AsTransient() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("service unavailable"), 503))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassify504AsTransient() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("gateway timeout"), 504))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassify400AsPermanent() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("bad request"), 400))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.PERMANENT);
    }

    @Test
    void shouldClassify401AsPermanent() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("unauthorized"), 401))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.PERMANENT);
    }

    @Test
    void shouldClassify403AsPermanent() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("forbidden"), 403))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.PERMANENT);
    }

    @Test
    void shouldClassify422AsPermanent() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("unprocessable"), 422))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.PERMANENT);
    }

    @Test
    void shouldClassifyHttpTimeoutExceptionAsTransient() {
        assertThat(LlmErrorClassifier.classify(new HttpTimeoutException("timeout"), 0))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassifyConnectExceptionAsTransient() {
        assertThat(LlmErrorClassifier.classify(new ConnectException("connection refused"), 0))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassifyNestedTimeoutAsTransient() {
        RuntimeException wrapped = new RuntimeException("wrap", new HttpTimeoutException("timeout"));
        assertThat(LlmErrorClassifier.classify(wrapped, 0))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.TRANSIENT);
    }

    @Test
    void shouldClassify500AsUnknown() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("server error"), 500))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.UNKNOWN);
    }

    @Test
    void shouldClassifyUnknownExceptionAsUnknown() {
        assertThat(LlmErrorClassifier.classify(new IllegalArgumentException("unknown"), 0))
                .isEqualTo(LlmErrorClassifier.LlmErrorClass.UNKNOWN);
    }
}
