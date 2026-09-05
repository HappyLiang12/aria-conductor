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

    /**
     * Sandbox-reachable host candidates in preference order: the override first
     * (when set), then the private v4 candidates ranked (172.16/12 podman/WSL
     * adapters, then 10/8, then 192.168/16). Consumers (OpenCodeAdkProvider
     * reachability probe) probe in order and pick the first candidate the sandbox
     * actually reaches — ranking alone is only a guess.
     */
    public List<String> resolveOrdered() {
        List<String> ordered = new ArrayList<>();
        if (override != null && !override.isBlank()) {
            ordered.add(override.trim());
        }
        candidates.stream()
                .filter(c -> isPrivateV4(c.address()))
                .sorted(Comparator.comparingInt(c -> rank(c.address())))
                .map(Candidate::address)
                .forEach(ordered::add);
        return ordered;
    }

    /** Sandbox-reachable host address, or empty when none is usable. */
    public Optional<String> resolve() {
        List<String> ordered = resolveOrdered();
        return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
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
