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
 * Proves the dashboard summary's active-set contract: {@code activeAgents} is the
 * live fleet ({@code HEALTHY + DEGRADED}), and the two parts are still reported
 * separately so the UI's "Healthy · N degraded" detail stays truthful.
 *
 * <p>Counting only healthy agents made a DEGRADED agent disappear from the KPI
 * while the same response reported it as degraded. These assertions use
 * before/after differences rather than absolute numbers because the shared H2
 * database may already contain unrelated agents.
 */
@AutoConfigureMockMvc
class DashboardSummaryIntegrationTest extends BaseH2IntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agentRepository;

    @Test
    void degradedAgentsAreActiveButNeverCountedAsHealthy() throws Exception {
        JsonNode before = fetchSummary();

        // activeAgents is by definition the live fleet: healthy + degraded.
        assertThat(before.get("activeAgents").asLong())
                .as("activeAgents must equal healthyAgents + degradedAgents")
                .isEqualTo(before.get("healthyAgents").asLong() + before.get("degradedAgents").asLong());

        // A new DEGRADED agent is active: it must raise both degradedAgents and activeAgents by 1.
        agentRepository.save(newAgent("summary-degraded-", HealthStatus.DEGRADED));

        JsonNode afterDegraded = fetchSummary();
        assertThat(afterDegraded.get("degradedAgents").asLong())
                .as("inserting a DEGRADED agent must raise degradedAgents by exactly 1")
                .isEqualTo(before.get("degradedAgents").asLong() + 1);
        assertThat(afterDegraded.get("activeAgents").asLong())
                .as("a DEGRADED agent must raise activeAgents by exactly 1")
                .isEqualTo(before.get("activeAgents").asLong() + 1);
        assertThat(afterDegraded.get("healthyAgents").asLong())
                .as("a DEGRADED agent must not affect healthyAgents")
                .isEqualTo(before.get("healthyAgents").asLong());

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
                .as("activeAgents must equal healthyAgents + degradedAgents after both inserts")
                .isEqualTo(afterHealthy.get("healthyAgents").asLong()
                        + afterHealthy.get("degradedAgents").asLong());
    }

    @Test
    void unhealthyAndRetiredAgentsAreNotActive() throws Exception {
        JsonNode before = fetchSummary();

        agentRepository.save(newAgent("summary-unhealthy-", HealthStatus.UNHEALTHY));
        agentRepository.save(newAgent("summary-retired-", HealthStatus.RETIRED));

        JsonNode after = fetchSummary();
        assertThat(after.get("activeAgents").asLong())
                .as("UNHEALTHY and RETIRED agents must not raise activeAgents")
                .isEqualTo(before.get("activeAgents").asLong());
        assertThat(after.get("healthyAgents").asLong())
                .as("UNHEALTHY and RETIRED agents must not raise healthyAgents")
                .isEqualTo(before.get("healthyAgents").asLong());
        assertThat(after.get("degradedAgents").asLong())
                .as("UNHEALTHY and RETIRED agents must not raise degradedAgents")
                .isEqualTo(before.get("degradedAgents").asLong());
    }

    private Agent newAgent(String namePrefix, HealthStatus healthStatus) {
        return Agent.builder()
                .name(namePrefix + UUID.randomUUID())
                .role("test")
                .agentType(AgentType.ADK)
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
