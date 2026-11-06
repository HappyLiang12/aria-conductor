package io.aria.conductor.execution.runtime.sandbox;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.runtime.PreparedEnvironment;

import java.net.URI;

/**
 * The bind address a core inside a SANDBOX sandbox must use.
 *
 * <p>A sandbox placement publishes the core's in-sandbox port through the
 * OpenSandbox server's proxy ({@code http://<host>:<published>/proxy/<port>}),
 * and that URL is what the coordinator dials as the session endpoint. It is NOT
 * a bind address: the core runs inside the sandbox, so it must listen on its
 * loopback at the INNER port the proxy forwards to. Binding the published
 * host/port inside the container would leave the proxy forwarding to a closed
 * port, which is exactly the failure a qoder/SANDBOX run reported before this
 * helper existed.
 *
 * <p>HOST placements never take this path: their endpoint IS the core's own
 * listen address.
 */
public final class SandboxBind {

    private SandboxBind() {
    }

    /** True when the environment is a sandbox placement whose endpoint is a proxy URL. */
    public static boolean isSandboxProxy(PreparedEnvironment environment) {
        return environment.mode() == ExecutionMode.SANDBOX;
    }

    /**
     * The loopback host every sandbox core binds: loopback only, because the
     * proxy runs in the sandbox's own network namespace (and the Qoder bridge
     * accepts exactly the loopback literals).
     */
    public static String loopbackHost() {
        return "127.0.0.1";
    }

    /**
     * The inner port the proxy forwards to, read from the endpoint's
     * {@code /proxy/<port>} path. A sandbox endpoint without it is refused rather
     * than guessed: binding an unknown port is a run that can only fail.
     */
    public static int innerPort(URI endpoint) {
        String path = endpoint == null ? null : endpoint.getPath();
        if (path == null) {
            throw new IllegalStateException(
                    "A sandbox endpoint must carry the proxied inner port as /proxy/<port>, got: " + endpoint);
        }
        int index = path.lastIndexOf("/proxy/");
        if (index < 0) {
            throw new IllegalStateException(
                    "A sandbox endpoint must carry the proxied inner port as /proxy/<port>, got: " + endpoint);
        }
        String tail = path.substring(index + "/proxy/".length());
        int slash = tail.indexOf('/');
        if (slash >= 0) {
            tail = tail.substring(0, slash);
        }
        try {
            int port = Integer.parseInt(tail.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "A sandbox endpoint must carry the proxied inner port as /proxy/<port>, got: " + endpoint);
        }
    }
}
