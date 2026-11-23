package io.aria.conductor.aria.live;

import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveHandshakeInterceptorTest {

    private static final String OPERATOR = "live-handshake-operator";

    private final ActorTokenService actorTokens = new ActorTokenService();
    private final OperatorSessionService sessions = new OperatorSessionService(
            OPERATOR, Duration.ofHours(8), OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false);

    private LiveHandshakeInterceptor interceptor(String trustedProxies) {
        return new LiveHandshakeInterceptor(new OperatorAuthorityResolver(sessions, actorTokens, trustedProxies));
    }

    @Test
    void operatorBearerCompletesTheHandshakeFromAnywhere() {
        ServerHttpRequest request = request("192.168.0.5", "Bearer " + OPERATOR, null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor("").beforeHandshake(request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(LiveHandshakeInterceptor.CLIENT_ADDRESS_ATTRIBUTE, "192.168.0.5");
        verify(response, never()).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anonymousRemotePeerIsUnauthorized() {
        ServerHttpRequest request = request("192.168.0.5", null, null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor("").beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void workerBearerIsForbiddenEvenFromLoopback() {
        String worker = actorTokens.issueWorker(UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(5)));
        ServerHttpRequest request = request("127.0.0.1", "Bearer " + worker, null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor("").beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void anonymousLoopbackIsTheLocalOperator() {
        ServerHttpRequest request = request("127.0.0.1", null, null);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor("").beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(LiveHandshakeInterceptor.CLIENT_ADDRESS_ATTRIBUTE, "127.0.0.1");
    }

    @Test
    void forwardedClientBehindATrustedProxyIsTheDecisionAddress() {
        ServerHttpRequest request = request("127.0.0.1", null, "203.0.113.9");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor("127.0.0.1").beforeHandshake(request, response,
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void offeredSubprotocolIsRecordedForTheHandler() {
        ServerHttpRequest request = request("127.0.0.1", null, null);
        Map<String, Object> attributes = new HashMap<>();

        interceptor("").beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(attributes).containsEntry(LiveHandshakeInterceptor.OFFERED_PROTOCOL_ATTRIBUTE, "aria-live.v1");
    }

    @Test
    void aRequestWithoutASocketAddressIsNotLoopback() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getRemoteAddress()).thenReturn(null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor("").beforeHandshake(request, response,
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(response, never()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    private static ServerHttpRequest request(String remoteAddress, String authorization, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(LiveWebSocketHandler.PROTOCOL_HEADER, "aria-live.v1");
        if (authorization != null) {
            headers.add(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(remoteAddress, 5000));
        return request;
    }
}
