package io.aria.conductor.execution.llm;

import io.aria.conductor.agent.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmClientRetryDecoratorTest {

    @Mock private LlmClient delegate;
    @Mock private SystemConfigService configService;

    private LlmClientRetryDecorator decorator;

    @BeforeEach
    void setUp() {
        when(configService.getInt("llm.retry.max.attempts", 3, 0, 10)).thenReturn(2);
        when(configService.getInt("llm.retry.backoff.base.ms", 2000, 500, 30000)).thenReturn(100); // fast tests
        decorator = new LlmClientRetryDecorator(delegate, configService);
    }

    @Test
    void shouldSucceedOnFirstAttempt() {
        LlmRequest request = LlmRequest.of("test-model", List.of(LlmMessage.user("hello")), 100);
        LlmResponse expected = new LlmResponse("hi", 10, 5, "stop", List.of());
        when(delegate.complete(request)).thenReturn(expected);

        LlmResponse response = decorator.complete(request);

        assertThat(response.content()).isEqualTo("hi");
        verify(delegate, times(1)).complete(request);
    }

    @Test
    void shouldRetryOn504AndSucceed() {
        LlmRequest request = LlmRequest.of("test-model", List.of(LlmMessage.user("hello")), 100);
        LlmResponse expected = new LlmResponse("hi", 10, 5, "stop", List.of());
        when(delegate.complete(request))
                .thenThrow(new LlmHttpException(504, "timeout"))
                .thenReturn(expected);

        LlmResponse response = decorator.complete(request);

        assertThat(response.content()).isEqualTo("hi");
        verify(delegate, times(2)).complete(request);
    }

    @Test
    void shouldRetryOn429WithBackoff() {
        LlmRequest request = LlmRequest.of("test-model", List.of(LlmMessage.user("hello")), 100);
        LlmResponse expected = new LlmResponse("ok", 10, 5, "stop", List.of());
        when(delegate.complete(request))
                .thenThrow(new LlmHttpException(429, "rate limit"))
                .thenThrow(new LlmHttpException(429, "rate limit"))
                .thenReturn(expected);

        LlmResponse response = decorator.complete(request);

        assertThat(response.content()).isEqualTo("ok");
        verify(delegate, times(3)).complete(request);
    }

    @Test
    void shouldFailAfterMaxRetries() {
        LlmRequest request = LlmRequest.of("test-model", List.of(LlmMessage.user("hello")), 100);
        when(delegate.complete(request)).thenThrow(new LlmHttpException(504, "timeout"));

        assertThatThrownBy(() -> decorator.complete(request))
                .isInstanceOf(LlmHttpException.class)
                .hasMessageContaining("504");

        // 1 initial + 2 retries = 3 attempts (maxRetries=2)
        verify(delegate, times(3)).complete(request);
    }

    @Test
    void shouldFailImmediatelyOn401() {
        LlmRequest request = LlmRequest.of("test-model", List.of(LlmMessage.user("hello")), 100);
        when(delegate.complete(request)).thenThrow(new LlmHttpException(401, "unauthorized"));

        assertThatThrownBy(() -> decorator.complete(request))
                .isInstanceOf(LlmHttpException.class)
                .hasMessageContaining("401");

        verify(delegate, times(1)).complete(request);
    }
}
