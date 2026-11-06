package io.aria.conductor.dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link WebSocketConfig} registers the simple broker prefix, the application
 * destination prefix and the STOMP endpoint with permissive origin patterns, by asserting the
 * calls made against mocked registries.
 */
class WebSocketConfigTest {

    private final WebSocketConfig config = new WebSocketConfig();

    @Test
    void configureMessageBroker_enablesTopicBrokerAndAppPrefix() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void registerStompEndpoints_addsEventsEndpointWithWildcardOrigins() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws/events")).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws/events");
        verify(registration).setAllowedOriginPatterns("*");
    }
}
