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
}
