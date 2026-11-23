package io.aria.conductor.aria.live;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Registers the live socket at exactly {@code /api/v1/aria/live} (spec X1).
 *
 * <p>{@link LiveUpgradeFilter} is a component of its own so a plain GET is
 * answered 426 without an MVC mapping shadowing the handler here.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(LiveProperties.class)
public class LiveWebSocketConfig implements WebSocketConfigurer {

    /**
     * The same browser origins the REST CORS policy admits (act-dashboard-api
     * WebConfig). A native client (the phone, the smoke script) sends no Origin
     * and is unaffected; a page on any other origin is refused at the handshake,
     * so a cross-site tab cannot ride the loopback auto-authority.
     */
    private static final String[] ALLOWED_ORIGIN_PATTERNS = {"http://localhost:*", "http://127.0.0.1:*"};

    private final LiveWebSocketHandler handler;
    private final LiveHandshakeInterceptor interceptor;

    public LiveWebSocketConfig(LiveWebSocketHandler handler, LiveHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, LiveUpgradeFilter.PATH)
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
                .setHandshakeHandler(handshakeHandler());
    }

    /**
     * The supported-protocol list is what makes Spring echo {@code aria-live.v1}
     * when the client offers it (Spring 6.2 negotiates on the handshake handler,
     * not on the registration). An offer that does not match is still accepted
     * and refused by the handler after {@code ready}.
     */
    private static DefaultHandshakeHandler handshakeHandler() {
        DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();
        handshakeHandler.setSupportedProtocols(LiveWebSocketHandler.PROTOCOL);
        return handshakeHandler;
    }
}
