package io.aria.conductor.execution.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxHostResolverTest {

    private SandboxHostResolver.Candidate c(String name, String address) {
        return new SandboxHostResolver.Candidate(name, address);
    }

    @Test
    void overrideWins() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(c("eth0", "192.168.0.119")), "10.1.2.3");
        assertThat(resolver.resolve()).contains("10.1.2.3");
    }

    @Test
    void prefersPodmanWslAdapterRange() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("Ethernet", "192.168.0.119"),
                c("vEthernet (WSL (Hyper-V) firewall)", "172.30.112.1"),
                c("lo", "127.0.0.1")), "");
        // Spike 2026-09-05: 172.30.112.1 (podman/WSL host-side) reached the backend from a sandbox.
        assertThat(resolver.resolve()).contains("172.30.112.1");
    }

    @Test
    void skipsLoopbackAndLinkLocal() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("lo", "127.0.0.1"),
                c("bridge", "169.254.1.2"),
                c("Ethernet", "192.168.0.119")), "");
        assertThat(resolver.resolve()).contains("192.168.0.119");
    }

    @Test
    void emptyWhenNothingUsable() {
        assertThat(SandboxHostResolver.over(List.of(c("lo", "127.0.0.1")), "").resolve()).isEmpty();
    }

    @Test
    void excludesPublic172_32_andAbove() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("docker0", "172.32.0.1"),
                c("Ethernet", "192.168.0.119")), "");
        assertThat(resolver.resolve()).contains("192.168.0.119");
    }

    @Test
    void includesFull172_16_to_172_31_range() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("Ethernet", "192.168.0.119"),
                c("vEthernet (WSL)", "172.16.5.4")), "");
        assertThat(resolver.resolve()).contains("172.16.5.4");
    }

    // ---- resolveOrdered: ranked candidate list for the sandbox-internal probe ----

    @Test
    void resolveOrdered_overrideFirstThenRankedCandidates() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("Ethernet", "192.168.0.119"),
                c("bond0", "10.0.0.5"),
                c("vEthernet (WSL)", "172.30.112.1")), "203.0.113.7");
        // override first, then podman/WSL 172.16/12 (rank 0), 10/8 (rank 1), 192.168 (rank 2)
        assertThat(resolver.resolveOrdered())
                .containsExactly("203.0.113.7", "172.30.112.1", "10.0.0.5", "192.168.0.119");
    }

    @Test
    void resolveOrdered_excludesNonPrivateAddresses() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("lo", "127.0.0.1"),
                c("bridge", "169.254.1.2"),
                c("docker0", "172.32.0.1"),
                c("Ethernet", "192.168.0.119")), "");
        assertThat(resolver.resolveOrdered()).containsExactly("192.168.0.119");
    }

    @Test
    void resolveOrdered_emptyWhenNothingUsable() {
        assertThat(SandboxHostResolver.over(List.of(c("lo", "127.0.0.1")), "").resolveOrdered()).isEmpty();
    }

    @Test
    void resolve_delegatesToFirstOrderedCandidate() {
        SandboxHostResolver resolver = SandboxHostResolver.over(List.of(
                c("Ethernet", "192.168.0.119"),
                c("vEthernet (WSL)", "172.30.112.1")), "");
        assertThat(resolver.resolve()).contains(resolver.resolveOrdered().get(0));
    }
}
