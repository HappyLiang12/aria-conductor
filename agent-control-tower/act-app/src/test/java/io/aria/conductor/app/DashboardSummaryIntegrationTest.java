package io.aria.conductor.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.agent.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that the dashboard summary reports healthy and degraded agents separately, so a
 * DEGRADED agent is never surfaced as healthy (the UI rendered {@code activeAgents} as
 * "N Agents Online" next to "Healthy & responsive").
 *
 * <p>{@code activeAgents} must equal the healthy-only count. These assertions use
 * before/after differences rather than absolute numbers because the shared H2 database may
 * already contain unrelated agents: a regression to {@code HEALTHY + DEGRADED} would leave
 * {@code activeAgents} unchanged after a healthy insert, and would bump it after a degraded
 * insert, both of which are detected below.
 */
@AutoConfigureMockMvc
class DashboardSummaryIntegrationTest extends BaseH2IntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Test
    void degradedAgentsAreNotCountedAsHealthy() throws Exception {
        JsonNode before = fetchSummary();

        // activeAgents is by definition the healthy-only count.
        assertThat(before.get("activeAgents").asLong())
                .as("activeAgents must equal healthyAgents")
                .isEqualTo(before.get("healthyAgents").asLong());

        // A new DEGRADED agent must be reported as degraded, but must NOT increase activeAgents.
        agentRepository.save(newAgent("summary-degraded-", HealthStatus.DEGRADED));

        JsonNode afterDegraded = fetchSummary();
        assertThat(afterDegraded.get("degradedAgents").asLong())
                .as("inserting a DEGRADED agent must raise degradedAgents by exactly 1")
                .isEqualTo(before.get("degradedAgents").asLong() + 1);
        assertThat(afterDegraded.get("activeAgents").asLong())
                .as("a DEGRADED agent must not raise activeAgents")
                .isEqualTo(before.get("activeAgents").asLong());
        assertThat(afterDegraded.get("healthyAgents").asLong())
                .as("a DEGRADED agent must not affect healthyAgents")
                .isEqualTo(before.get("healthyAgents").asLong());
        assertThat(afterDegraded.get("activeAgents").asLong())
                .as("activeAgents must equal healthyAgents after the degraded insert")
                .isEqualTo(afterDegraded.get("healthyAgents").asLong());

        // A new HEALTHY agent must raise both activeAgents and healthyAgents by exactly 1.
        agentRepository.save(newAgent("summary-healthy-", HealthStatus.HEALTHY));

        JsonNode afterHealthy = fetchSummary();
        assertThat(afterHealthy.get("healthyAgents").asLong())
                .as("inserting a HEALTHY agent must raise healthyAgents by exactly 1")
                .isEqualTo(afterDegraded.get("healthyAgents").asLong() + 1);
        assertThat(afterHealthy.get("activeAgents").asLong())
                .as("a HEALTHY agent must raise activeAgents by exactly 1")
                .isEqualTo(afterDegraded.get("activeAgents").asLong() + 1);
        assertThat(afterHealthy.get("degradedAgents").asLong())
                .as("a HEALTHY agent must not affect degradedAgents")
                .isEqualTo(afterDegraded.get("degradedAgents").asLong());
        assertThat(afterHealthy.get("activeAgents").asLong())
                .as("activeAgents must equal healthyAgents after the healthy insert")
                .isEqualTo(afterHealthy.get("healthyAgents").asLong());
    }

    private Agent newAgent(String namePrefix, HealthStatus healthStatus) {
        return Agent.builder()
                .name(namePrefix + UUID.randomUUID())
                .role("test")
                .agentType(AgentType.NATIVE)
                .healthStatus(healthStatus)
                .build();
    }

    private JsonNode fetchSummary() throws Exception {
        String body = mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }
}
