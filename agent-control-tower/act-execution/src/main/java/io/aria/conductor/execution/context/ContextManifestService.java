package io.aria.conductor.execution.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.context.ContextRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the provenance of one assembly (spec §6.6, §8.5): one row per
 * (run, turn) carrying section ids, source ids, counts, estimates, drops and
 * degradation reasons — never prompt text.
 *
 * <p>A retried assembly for the same turn updates the row that turn already
 * owns instead of inserting a second one, so the {@code (run_id, turn_number)}
 * unique key is never violated and the row always describes the envelope that
 * was last sent.
 */
@Service
public class ContextManifestService {

    private final ContextManifestRepository repository;
    private final ObjectMapper mapper;

    public ContextManifestService(ContextManifestRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public UUID record(ContextRequest request, AssembledContext assembled) {
        ContextManifest row = repository.findByRunIdOrderByTurnNumberAsc(request.runId()).stream()
                .filter(existing -> existing.getTurnNumber() == request.turnNumber())
                .findFirst()
                .orElseGet(() -> ContextManifest.builder()
                        .id(UUID.randomUUID())
                        .runId(request.runId())
                        .turnNumber(request.turnNumber())
                        .build());
        row.setConversationId(request.conversationId());
        row.setAgentId(request.agentId());
        row.setProviderId(request.providerId());
        row.setAdapter(request.adapter().name());
        row.setSectionsJson(json(assembled.sections().stream().map(section -> Map.of(
                "id", section.id(),
                "sources", section.sourceIds(),
                "chars", section.estimatedChars(),
                "tokens", section.estimatedTokens(),
                "truncated", section.truncated())).toList()));
        row.setDroppedJson(json(assembled.dropped()));
        row.setDegradedJson(json(assembled.degraded()));
        row.setMaxChars(request.maxChars());
        row.setEstimatedTokens(assembled.estimatedTokens());
        row.setCreatedAt(Instant.now());
        repository.save(row);
        return row.getId();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest serialization failed", e);
        }
    }
}
