package io.aria.conductor.aria.live;

import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Authenticates the live upgrade before it completes (spec X1): it calls the one
 * operator decision — {@link OperatorAuthorityResolver#authorizeTransport} — so
 * the WebSocket cannot drift from the REST rule. A missing/invalid operator
 * credential answers 401, a worker credential 403, both on the handshake
 * response, so the client never gets an open socket.
 *
 * <p>It also records the offered subprotocol for the handler: the handshake
 * succeeds for any offer (or none) and the handler closes 4002 after
 * {@code ready} only when a protocol was offered and the one Spring accepted is
 * not {@code aria-live.v1}.
 */
@Component
public class LiveHandshakeInterceptor implements HandshakeInterceptor {

    /** Offered {@code Sec-WebSocket-Protocol}, verbatim, or {@code null} when the client sent none. */
    static final String OFFERED_PROTOCOL_ATTRIBUTE = "aria.live.offeredProtocol";
    /** Trusted-proxy-aware client address the decision was made on. */
    static final String CLIENT_ADDRESS_ATTRIBUTE = "aria.live.clientAddress";

    private static final Logger log = LoggerFactory.getLogger(LiveHandshakeInterceptor.class);
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final OperatorAuthorityResolver authority;

    public LiveHandshakeInterceptor(OperatorAuthorityResolver authority) {
        this.authority = authority;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String clientAddress = authority.clientAddress(peerAddress(request), request.getHeaders().getFirst(FORWARDED_FOR));
        try {
            authority.authorizeTransport(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION), clientAddress);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            log.debug("aria live handshake refused: reason=worker_credential");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        } catch (SecurityException e) {
            log.debug("aria live handshake refused: reason=no_operator_authority");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(CLIENT_ADDRESS_ATTRIBUTE, clientAddress);
        attributes.put(OFFERED_PROTOCOL_ATTRIBUTE, request.getHeaders().getFirst(LiveWebSocketHandler.PROTOCOL_HEADER));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // Nothing to do: the session attributes set before the upgrade carry what
        // the handler needs.
    }

    private static String peerAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? null : remote.getAddress().getHostAddress();
    }
}
