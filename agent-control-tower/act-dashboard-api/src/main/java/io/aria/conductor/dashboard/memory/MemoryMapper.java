package io.aria.conductor.dashboard.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.dashboard.memory.dto.MemoryAssertionView;
import io.aria.conductor.dashboard.memory.dto.MemoryKnowledgeLinkView;
import io.aria.conductor.dashboard.memory.dto.MemoryManifestSection;
import io.aria.conductor.dashboard.memory.dto.MemoryManifestView;
import io.aria.conductor.dashboard.memory.dto.MemoryRelationView;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryRelation;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps act-memory rows and manifest rows to the REST views.
 * <p>
 * The manifest mapping is a whitelist: only section id, source ids, sizes, and the
 * truncation flag are copied out of {@code sections_json}. Anything else the stored JSON
 * might contain is never rendered, so this route cannot become a prompt-content leak
 * (spec §6.6, §9).
 */
final class MemoryMapper {

    private MemoryMapper() {
    }

    static MemoryRelationView relationView(MemoryRelation relation) {
        return new MemoryRelationView(
                idString(relation.getId()),
                idString(relation.getFromEntityId()),
                relation.getPredicate(),
                idString(relation.getToEntityId()),
                name(relation.getStatus()),
                name(relation.getOrigin()),
                name(relation.getSensitivity()),
                relation.getConfidence(),
                relation.getValidFrom(),
                relation.getValidTo());
    }

    static MemoryKnowledgeLinkView linkView(MemoryKnowledgeLink link) {
        return new MemoryKnowledgeLinkView(
                idString(link.getId()),
                idString(link.getEntityId()),
                idString(link.getKnowledgeItemId()),
                name(link.getStatus()),
                link.getCreatedAt(),
                link.getDecidedAt(),
                link.getDecidedBy());
    }

    static MemoryAssertionView assertionView(MemoryAssertion assertion) {
        return new MemoryAssertionView(
                idString(assertion.getId()),
                name(assertion.getSubjectType()),
                idString(assertion.getSubjectId()),
                name(assertion.getAction()),
                name(assertion.getActorType()),
                assertion.getActorRef(),
                assertion.getSourceRef(),
                assertion.getBeforeJson(),
                assertion.getAfterJson(),
                assertion.getCreatedAt());
    }

    static MemoryManifestView manifestView(ContextManifest row, ObjectMapper mapper) {
        return new MemoryManifestView(
                idString(row.getId()),
                idString(row.getRunId()),
                row.getTurnNumber(),
                row.getConversationId(),
                idString(row.getAgentId()),
                idString(row.getProviderId()),
                row.getAdapter(),
                row.getMaxChars(),
                row.getEstimatedTokens(),
                row.getCreatedAt(),
                sections(row.getSectionsJson(), mapper),
                strings(row.getDroppedJson(), mapper),
                strings(row.getDegradedJson(), mapper));
    }

    /**
     * Copies only the known section fields. A malformed payload yields an empty list: a
     * diagnostic row must never fail the read path, and it must never fall back to echoing
     * the raw JSON.
     */
    private static List<MemoryManifestSection> sections(String json, ObjectMapper mapper) {
        JsonNode root = parse(json, mapper);
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<MemoryManifestSection> sections = new ArrayList<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                continue;
            }
            sections.add(new MemoryManifestSection(
                    text(node, "id"),
                    stringList(node.get("sources")),
                    integer(node, "chars"),
                    integer(node, "tokens"),
                    node.path("truncated").asBoolean(false)));
        }
        return sections;
    }

    private static List<String> strings(String json, ObjectMapper mapper) {
        JsonNode root = parse(json, mapper);
        return stringList(root);
    }

    private static JsonNode parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry != null && entry.isTextual()) {
                values.add(entry.asText());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? 0 : value.asInt();
    }

    private static String idString(Object id) {
        return id == null ? null : id.toString();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
