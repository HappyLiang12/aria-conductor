package io.aria.conductor.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain-specific assertion helpers. Wrap repetitive AssertJ chains so
 * tests read closer to a specification.
 */
public final class AssertionHelpers {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssertionHelpers() {
    }

    public static void assertAgentActive(Agent agent) {
        assertThat(agent).as("agent").isNotNull();
        assertThat(agent.getId()).as("agent.id").isNotNull();
        assertThat(agent.getRetiredAt()).as("agent.retiredAt").isNull();
        assertThat(agent.getHealthStatus())
                .as("agent.healthStatus")
                .isIn(HealthStatus.HEALTHY, HealthStatus.DEGRADED);
    }

    public static void assertRunCompleted(Run run) {
        assertThat(run).as("run").isNotNull();
        assertThat(run.getStatus()).as("run.status").isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getCompletedAt()).as("run.completedAt").isNotNull();
        assertThat(run.getErrorMessage()).as("run.errorMessage").isNull();
    }

    public static void assertKnowledgeApproved(KnowledgeItem item) {
        assertThat(item).as("knowledgeItem").isNotNull();
        assertThat(item.getStatus())
                .as("knowledgeItem.status")
                .isEqualTo(KnowledgeStatus.APPROVED);
        assertThat(item.getCurrentVersion())
                .as("knowledgeItem.currentVersion")
                .isNotBlank();
    }

    public static void assertApprovalPending(Approval approval) {
        assertThat(approval).as("approval").isNotNull();
        assertThat(approval.getStatus())
                .as("approval.status")
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.getDecidedAt()).as("approval.decidedAt").isNull();
    }

    /**
     * Assert a JSON document contains the supplied key with the expected value.
     * The key may be a simple field or a slash-separated JSON pointer (e.g.
     * {@code "data/items/0/name"}). Use {@code null} expected to assert presence
     * regardless of value.
     */
    public static void assertJsonContains(String json, String key, Object expected) {
        assertThat(json).as("json document").isNotBlank();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode node = key.contains("/")
                    ? root.at("/" + key.replaceFirst("^/", ""))
                    : root.get(key);
            assertThat(node).as("json key '%s'", key).isNotNull();
            assertThat(node.isMissingNode()).as("json key '%s' present", key).isFalse();
            if (expected != null) {
                assertThat(unwrap(node)).as("json key '%s' value", key).isEqualTo(expected);
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    private static Object unwrap(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNull()) return null;
        return node.toString();
    }
}
