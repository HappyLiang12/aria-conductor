package io.aria.conductor.aria.live;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One socket, one entry: the registry is what a later router reads to find a live run's
 * socket, and what a backend stop walks to reap every child.
 */
class LiveSessionRegistryTest {

    @Test
    void aSessionIsFoundByItsSocketIdAndForgottenWhenRemoved() {
        LiveSessionRegistry registry = new LiveSessionRegistry();
        LiveSession live = session("ws-1");

        registry.register(live);

        assertThat(registry.get("ws-1")).isSameAs(live);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.all()).containsExactly(live);

        assertThat(registry.remove("ws-1")).isSameAs(live);
        assertThat(registry.get("ws-1")).isNull();
        assertThat(registry.size()).isZero();
    }

    @Test
    void registeringTheSameSocketAgainReplacesItsEntry() {
        LiveSessionRegistry registry = new LiveSessionRegistry();
        registry.register(session("ws-1"));
        LiveSession replacement = session("ws-1");

        registry.register(replacement);

        assertThat(registry.get("ws-1")).isSameAs(replacement);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void anUnknownSocketResolvesToNull() {
        assertThat(new LiveSessionRegistry().get("nope")).isNull();
    }

    @Test
    void shutdownReapsEveryBridgeAndForgetsEverySocket() {
        LiveSessionRegistry registry = new LiveSessionRegistry();
        AtomicInteger closes = new AtomicInteger();
        LiveSession first = session("ws-1");
        LiveSession second = session("ws-2");
        first.bridge(bridge(closes::incrementAndGet));
        second.bridge(bridge(() -> {
            throw new IllegalStateException("already gone");
        }));
        registry.register(first);
        registry.register(second);

        registry.shutdown();

        assertThat(closes.get()).isEqualTo(1);
        assertThat(registry.size()).isZero();
    }

    private static LiveBridge bridge(Runnable onClose) {
        return new LiveBridge() {
            @Override
            public void send(String json) {
            }

            @Override
            public void sendBinary(byte[] pcm) {
            }

            @Override
            public void close() {
                onClose.run();
            }
        };
    }

    private static LiveSession session(String wsId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(wsId);
        return new LiveSession("id-" + wsId, "conv", ws, null);
    }
}
