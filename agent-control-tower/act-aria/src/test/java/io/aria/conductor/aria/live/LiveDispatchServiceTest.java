package io.aria.conductor.aria.live;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-socket dispatch registry (Phase 4): a child run belongs to the socket that
 * approved it, leaves on its terminal status, and survives another socket's cleanup.
 */
class LiveDispatchServiceTest {

    @Test
    void aRunBelongsToOneSocketAndIsRemovedOnce() {
        LiveDispatchService service = new LiveDispatchService();
        LiveSession one = new LiveSession("s1", null, null);
        LiveSession two = new LiveSession("s2", null, null);
        UUID run = UUID.randomUUID();

        service.register(run, one, "Analyst");
        assertThat(service.find(run)).get()
                .extracting(LiveDispatchService.Dispatch::agentName).isEqualTo("Analyst");
        assertThat(service.remove(run)).isPresent();
        assertThat(service.remove(run)).isEmpty();

        service.register(run, two, "Analyst");
        service.removeSocket(one);
        assertThat(service.find(run)).as("another socket's cleanup leaves it").isPresent();
        service.removeSocket(two);
        assertThat(service.find(run)).isEmpty();
        assertThat(service.runIds()).isEmpty();
    }

    @Test
    void aMissingAgentNameIsAnEmptyStringNeverNull() {
        LiveDispatchService service = new LiveDispatchService();
        UUID run = UUID.randomUUID();
        service.register(run, new LiveSession("s1", null, null), null);
        assertThat(service.find(run)).get()
                .extracting(LiveDispatchService.Dispatch::agentName).isEqualTo("");
    }
}
