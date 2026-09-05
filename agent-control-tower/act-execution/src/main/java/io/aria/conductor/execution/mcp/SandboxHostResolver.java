package io.aria.conductor.execution.mcp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * Picks the host address sandbox containers can reach for the backend MCP endpoint.
 * Spike (2026-09-05, aria-conductor/opencode-sandbox:1.1 on podman machine): real host
 * IPs work (172.30.112.1 / 192.168.x.x -> 200), host.containers.internal (169.254.1.2)
 * is refused. Preference: podman/WSL host-side adapters (172.16/12) first, then other
 * private v4 ranges. aria.mcp.sandbox-host-address overrides everything.
 */
public final class SandboxHostResolver {

    public record Candidate(String interfaceName, String address) {}

    private final String override;
    private final List<Candidate> candidates;

    private SandboxHostResolver(String override, List<Candidate> candidates) {
        this.override = override;
        this.candidates = candidates;
    }

    public static SandboxHostResolver over(List<Candidate> candidates, String override) {
        return new SandboxHostResolver(override, candidates);
    }

    public static SandboxHostResolver fromSystemInterfaces(String override) {
        List<Candidate> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        found.add(new Candidate(ni.getDisplayName(), addr.getHostAddress()));
                    }
                }
            }
        } catch (Exception e) {
            // caller falls back to override or empty; caller logs the outcome
        }
        return new SandboxHostResolver(override, found);
    }

    /** Sandbox-reachable host address, or empty when none is usable. */
    public Optional<String> resolve() {
        if (override != null && !override.isBlank()) {
            return Optional.of(override.trim());
        }
        return candidates.stream()
                .filter(c -> isPrivateV4(c.address()))
                .min(Comparator.comparingInt(c -> rank(c.address())))
                .map(Candidate::address);
    }

    private static boolean isPrivateV4(String address) {
        int first = firstOctet(address);
        if (address.startsWith("127.") || address.startsWith("169.254.") || address.startsWith("0.")) {
            return false;
        }
        return first == 10 || (first == 172 && secondOctet(address) >= 16 && secondOctet(address) <= 31)
                || first == 192 && address.startsWith("192.168.");
    }

    private static int rank(String address) {
        int first = firstOctet(address);
        if (first == 172 && secondOctet(address) >= 16 && secondOctet(address) <= 31) {
            return 0; // podman/WSL host-side adapter range (spike-proven)
        }
        if (first == 10) {
            return 1;
        }
        return 2; // 192.168.x
    }

    private static int firstOctet(String address) {
        try {
            return Integer.parseInt(address.substring(0, address.indexOf('.')));
        } catch (Exception e) {
            return -1;
        }
    }

    private static int secondOctet(String address) {
        try {
            int firstDot = address.indexOf('.');
            return Integer.parseInt(address.substring(firstDot + 1, address.indexOf('.', firstDot + 1)));
        } catch (Exception e) {
            return -1;
        }
    }
}
