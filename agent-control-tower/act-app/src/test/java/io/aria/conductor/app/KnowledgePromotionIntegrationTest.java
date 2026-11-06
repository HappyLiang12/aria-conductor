package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST journey over the knowledge governance lifecycle:
 * submit -> review APPROVE -> promote (new item with major version bump + lineage
 * description), and the REJECTED path which is terminal for further review/retire.
 */
@Import(NoopLlmTestConfig.class)
class KnowledgePromotionIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    // ---- helpers ----

    private Map<String, Object> submitKnowledge(String name, String type, String content) {
        Map<String, Object> request = Map.of(
                "name", name,
                "type", type,
                "description", "Promotion journey fixture",
                "content", content
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/knowledge", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<Map<String, Object>> getVersions(String itemId) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/knowledge/" + itemId + "/versions", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // ==================== APPROVE + promote journey ====================

    @Test
    void submitApprovePromote_createsNewPendingItemWithMajorVersionAndLineage() {
        String name = "promo-skill-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> created = submitKnowledge(name, "SKILL", "echo hello");
        String itemId = (String) created.get("id");

        // Fresh submission is PENDING at the initial minor version
        assertThat(created.get("status")).isEqualTo("PENDING");
        assertThat(created.get("currentVersion")).isEqualTo("v0.1.0");

        // Review APPROVE moves item and latest version to APPROVED
        ResponseEntity<Map> review = restTemplate.postForEntity(
                "/api/v1/knowledge/" + itemId + "/review",
                Map.of("decision", "APPROVED", "reason", "verified"), Map.class);
        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review.getBody().get("status")).isEqualTo("APPROVED");

        List<Map<String, Object>> sourceVersions = getVersions(itemId);
        assertThat(sourceVersions).hasSize(1);
        assertThat(sourceVersions.get(0).get("version")).isEqualTo("v0.1.0");
        assertThat(sourceVersions.get(0).get("status")).isEqualTo("APPROVED");
        assertThat(sourceVersions.get(0).get("approvedAt")).isNotNull();

        // Promote to a TOOL: brand-new item, v1.0.0, PENDING again, lineage in description
        ResponseEntity<Map> promote = restTemplate.postForEntity(
                "/api/v1/knowledge/" + itemId + "/promote",
                Map.of("targetType", "TOOL", "targetName", name + "-tool"), Map.class);
        assertThat(promote.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> promoted = promote.getBody();
        String promotedId = (String) promoted.get("id");
        assertThat(promotedId).isNotEqualTo(itemId);
        assertThat(promoted.get("name")).isEqualTo(name + "-tool");
        assertThat(promoted.get("type")).isEqualTo("TOOL");
        assertThat(promoted.get("status")).isEqualTo("PENDING");
        assertThat(promoted.get("currentVersion")).isEqualTo("v1.0.0");
        assertThat(promoted.get("description")).isEqualTo("Promoted from SKILL: " + name);

        // Promoted item carries the source content forward as its own PENDING v1.0.0
        List<Map<String, Object>> promotedVersions = getVersions(promotedId);
        assertThat(promotedVersions).hasSize(1);
        assertThat(promotedVersions.get(0).get("version")).isEqualTo("v1.0.0");
        assertThat(promotedVersions.get(0).get("status")).isEqualTo("PENDING");
        assertThat(promotedVersions.get(0).get("content")).isEqualTo("echo hello");

        // Promotion must not mutate the approved source item
        ResponseEntity<Map> source = restTemplate.getForEntity(
                "/api/v1/knowledge/" + itemId, Map.class);
        assertThat(source.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(source.getBody().get("currentVersion")).isEqualTo("v0.1.0");
    }

    // ==================== REJECTED path ====================

    @Test
    void rejectedItem_isTerminal_forReviewAndRetire() {
        String name = "reject-skill-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> created = submitKnowledge(name, "SKILL", "rm -rf /tmp/x");
        String itemId = (String) created.get("id");

        ResponseEntity<Map> reject = restTemplate.postForEntity(
                "/api/v1/knowledge/" + itemId + "/review",
                Map.of("decision", "REJECTED", "reason", "dangerous content"), Map.class);
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reject.getBody().get("status")).isEqualTo("REJECTED");

        // Version history reflects the rejection
        List<Map<String, Object>> versions = getVersions(itemId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("status")).isEqualTo("REJECTED");

        // Re-review of a non-PENDING item is an invalid state transition -> 409
        ResponseEntity<Map> reReview = restTemplate.postForEntity(
                "/api/v1/knowledge/" + itemId + "/review",
                Map.of("decision", "APPROVED", "reason", "changed my mind"), Map.class);
        assertThat(reReview.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Retire is only valid from APPROVED -> rejected items stay REJECTED
        ResponseEntity<Map> retire = restTemplate.postForEntity(
                "/api/v1/knowledge/" + itemId + "/retire", Map.of(), Map.class);
        assertThat(retire.getStatusCode().is4xxClientError()).isTrue();

        ResponseEntity<Map> get = restTemplate.getForEntity(
                "/api/v1/knowledge/" + itemId, Map.class);
        assertThat(get.getBody().get("status")).isEqualTo("REJECTED");
    }

    // ==================== negative paths ====================

    @Test
    void review_unknownItem_returns404() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/knowledge/" + UUID.randomUUID() + "/review",
                Map.of("decision", "APPROVED"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void submit_withoutContent_isRejectedByValidation() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/knowledge",
                Map.of("name", "no-content", "type", "SKILL"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
