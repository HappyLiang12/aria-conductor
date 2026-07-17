package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.common.event.KnowledgeRetiredEvent;
import io.aria.conductor.common.event.KnowledgeSubmittedEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.*;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.knowledge.dto.*;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeService implements KnowledgeContextProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String INITIAL_VERSION = "v0.1.0";

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeFileService fileService;
    private final ApplicationEventPublisher eventPublisher;

    public KnowledgeService(KnowledgeItemRepository itemRepository,
                            KnowledgeVersionRepository versionRepository,
                            KnowledgeFileService fileService,
                            ApplicationEventPublisher eventPublisher) {
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.fileService = fileService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public KnowledgeItemResponse submitKnowledge(CreateKnowledgeRequest request) {
        return submitKnowledge(request, null);
    }

    /**
     * Submit knowledge with optional YAML content (used for WORKFLOW type items).
     */
    @Transactional
    public KnowledgeItemResponse submitKnowledge(CreateKnowledgeRequest request, String yamlContent) {
        MDC.put("operation", "knowledge.submit");
        long start = System.currentTimeMillis();
        try {
            log.info("Submitting new knowledge item: name={}, type={}", request.getName(), request.getType());

            KnowledgeItem item = KnowledgeItem.builder()
                    .id(UUID.randomUUID())
                    .name(request.getName())
                    .type(request.getType())
                    .description(request.getDescription())
                    .status(KnowledgeStatus.PENDING)
                    .sensitivity(request.getSensitivity() != null ? request.getSensitivity() : Sensitivity.INTERNAL)
                    .currentVersion(INITIAL_VERSION)
                    .createdAt(Instant.now())
                    .build();

            MDC.put("entityId", item.getId().toString());

            String filePath = fileService.storeContent(item.getType(), item.getName(), INITIAL_VERSION, request.getContent());
            item.setFilePath(filePath);
            item = itemRepository.save(item);

            KnowledgeVersion version = KnowledgeVersion.builder()
                    .id(UUID.randomUUID())
                    .knowledgeItemId(item.getId())
                    .version(INITIAL_VERSION)
                    .status(VersionStatus.PENDING)
                    .content(request.getContent())
                    .yamlContent(yamlContent)
                    .createdAt(Instant.now())
                    .build();
            version = versionRepository.save(version);

            eventPublisher.publishEvent(new KnowledgeSubmittedEvent(
                    this, item.getId(), item.getType().name(), item.getName()));

            log.info("Knowledge item submitted successfully, duration={}ms", System.currentTimeMillis() - start);
            return toResponse(item, version);
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    /**
     * Returns the YAML content for a knowledge item's current (or specified) version.
     */
    @Transactional(readOnly = true)
    public String getYamlContent(UUID knowledgeItemId, String version) {
        KnowledgeItem item = itemRepository.findById(knowledgeItemId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", knowledgeItemId));

        String targetVersion = version != null ? version : item.getCurrentVersion();

        return versionRepository.findByKnowledgeItemIdAndVersion(item.getId(), targetVersion)
                .map(KnowledgeVersion::getYamlContent)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeItemResponse> listKnowledge(KnowledgeType type, KnowledgeStatus status) {
        List<KnowledgeItem> items;
        if (type != null && status != null) {
            items = itemRepository.findByTypeAndStatus(type, status);
        } else if (type != null) {
            items = itemRepository.findByType(type);
        } else if (status != null) {
            items = itemRepository.findByStatus(status);
        } else {
            items = itemRepository.findAll();
        }
        return items.stream().map(this::toResponseWithLatestVersion).toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeItemResponse getKnowledge(UUID id) {
        KnowledgeItem item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", id));
        return toResponseWithLatestVersion(item);
    }

    @Transactional
    public KnowledgeItemResponse updateKnowledge(UUID id, UpdateKnowledgeRequest request) {
        KnowledgeItem item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", id));

        log.info("Updating knowledge item: id={}, currentVersion={}", id, item.getCurrentVersion());

        if (request.getDescription() != null) {
            item.setDescription(request.getDescription());
        }
        if (request.getSensitivity() != null) {
            item.setSensitivity(request.getSensitivity());
        }

        String newVersion = incrementMinorVersion(item.getCurrentVersion());
        String content = request.getContent();
        if (content != null) {
            fileService.storeContent(item.getType(), item.getName(), newVersion, content);
        }

        KnowledgeVersion version = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(item.getId())
                .version(newVersion)
                .status(VersionStatus.PENDING)
                .content(content)
                .createdAt(Instant.now())
                .build();
        version = versionRepository.save(version);

        item.setCurrentVersion(newVersion);
        item.setStatus(KnowledgeStatus.PENDING);
        item = itemRepository.save(item);

        log.info("Knowledge item updated: id={}, newVersion={}", id, newVersion);
        return toResponse(item, version);
    }

    @Transactional
    public KnowledgeItemResponse reviewKnowledge(UUID id, ReviewDecisionRequest request) {
        MDC.put("operation", "knowledge.review");
        MDC.put("entityId", id.toString());
        long start = System.currentTimeMillis();
        try {
            KnowledgeItem item = itemRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", id));

            if (item.getStatus() != KnowledgeStatus.PENDING) {
                throw new InvalidStateTransitionException("KnowledgeItem",
                        item.getStatus().name(),
                        request.getDecision().name());
            }

            final UUID itemId = item.getId();
            final String currentVersion = item.getCurrentVersion();
            KnowledgeVersion latestVersion = versionRepository
                    .findByKnowledgeItemIdAndVersion(itemId, currentVersion)
                    .orElseThrow(() -> new ResourceNotFoundException("KnowledgeVersion",
                            itemId + "/" + currentVersion));

            if (request.getDecision() == ReviewDecisionRequest.ReviewDecision.APPROVED) {
                item.setStatus(KnowledgeStatus.APPROVED);
                latestVersion.setStatus(VersionStatus.APPROVED);
                latestVersion.setApprovedAt(Instant.now());
                log.info("Knowledge item approved successfully, duration={}ms", System.currentTimeMillis() - start);
            } else {
                item.setStatus(KnowledgeStatus.REJECTED);
                latestVersion.setStatus(VersionStatus.REJECTED);
                log.info("Knowledge item rejected, reason={}, duration={}ms",
                        request.getReason(), System.currentTimeMillis() - start);
            }

            item = itemRepository.save(item);
            latestVersion = versionRepository.save(latestVersion);

            if (item.getStatus() == KnowledgeStatus.APPROVED) {
                eventPublisher.publishEvent(new KnowledgeApprovedEvent(
                        this, item.getId(), item.getName(), item.getType().name()));
            }

            return toResponse(item, latestVersion);
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    @Transactional
    public KnowledgeItemResponse retireKnowledge(UUID id) {
        MDC.put("operation", "knowledge.retire");
        MDC.put("entityId", id.toString());
        long start = System.currentTimeMillis();
        try {
            KnowledgeItem item = itemRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", id));

            if (item.getStatus() != KnowledgeStatus.APPROVED) {
                throw new InvalidStateTransitionException("KnowledgeItem",
                        item.getStatus().name(), "RETIRED");
            }

            item.setStatus(KnowledgeStatus.RETIRED);
            item.setRetiredAt(Instant.now());
            item = itemRepository.save(item);

            eventPublisher.publishEvent(new KnowledgeRetiredEvent(
                    this, item.getId(), item.getName()));

            log.info("Knowledge item retired successfully, duration={}ms", System.currentTimeMillis() - start);
            return toResponseWithLatestVersion(item);
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    @Transactional(readOnly = true)
    public List<KnowledgeVersionResponse> getVersions(UUID itemId) {
        itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", itemId));

        return versionRepository.findByKnowledgeItemIdOrderByCreatedAtDesc(itemId)
                .stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeVersionResponse getVersionContent(UUID itemId, String version) {
        KnowledgeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", itemId));

        KnowledgeVersion kv = versionRepository.findByKnowledgeItemIdAndVersion(itemId, version)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeVersion", itemId + "/" + version));

        // Try filesystem first, fall back to DB content
        String content = fileService.readContent(item.getType(), item.getName(), version)
                .orElse(kv.getContent());

        return KnowledgeVersionResponse.builder()
                .id(kv.getId())
                .version(kv.getVersion())
                .status(kv.getStatus())
                .content(content)
                .createdAt(kv.getCreatedAt())
                .approvedAt(kv.getApprovedAt())
                .build();
    }

    @Transactional
    public KnowledgeItemResponse promoteKnowledgeItem(UUID sourceItemId, PromoteKnowledgeRequest request) {
        KnowledgeItem source = itemRepository.findById(sourceItemId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", sourceItemId));

        String name = request.getTargetName() != null ? request.getTargetName() : source.getName();
        String version = "v1.0.0"; // Major version bump on promotion

        // Get content from the current version of the source item
        String content = versionRepository.findByKnowledgeItemIdAndVersion(sourceItemId, source.getCurrentVersion())
                .map(KnowledgeVersion::getContent)
                .orElse("");

        KnowledgeItem promoted = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(request.getTargetType())
                .description("Promoted from " + source.getType() + ": " + source.getName())
                .status(KnowledgeStatus.PENDING)
                .sensitivity(source.getSensitivity())
                .currentVersion(version)
                .createdAt(Instant.now())
                .build();

        String filePath = fileService.storeContent(promoted.getType(), promoted.getName(), version, content);
        promoted.setFilePath(filePath);
        promoted = itemRepository.save(promoted);

        KnowledgeVersion kv = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(promoted.getId())
                .version(version)
                .status(VersionStatus.PENDING)
                .content(content)
                .createdAt(Instant.now())
                .build();
        kv = versionRepository.save(kv);

        log.info("Promoted knowledge item {} ({}) to new item {} ({})",
                sourceItemId, source.getType(), promoted.getId(), request.getTargetType());

        return toResponse(promoted, kv);
    }

    /**
     * Returns up to {@code limit} APPROVED knowledge items ordered by most recently updated.
     * Used by AriaService to inject knowledge context into the system prompt.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeItem> getApprovedKnowledgeContext(int limit) {
        List<KnowledgeItem> all = itemRepository.findByStatusOrderByUpdatedAtDesc(KnowledgeStatus.APPROVED);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public String buildKnowledgeContextPrompt(int limit) {
        List<KnowledgeItem> approved = getApprovedKnowledgeContext(limit);
        if (approved == null || approved.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## Knowledge Context\n");
        for (KnowledgeItem item : approved) {
            String content = item.getDescription() != null ? item.getDescription() : "";
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append("- **").append(item.getName()).append("** (")
              .append(item.getType()).append("): ").append(content).append("\n");
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public KnowledgeStatsResponse getStats() {
        List<KnowledgeItem> all = itemRepository.findAll();
        long total = all.size();

        var countByType = all.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getType().name(), Collectors.counting()));

        var countByStatus = all.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getStatus().name(), Collectors.counting()));

        return KnowledgeStatsResponse.builder()
                .totalItems(total)
                .countByType(countByType)
                .countByStatus(countByStatus)
                .build();
    }

    private String incrementMinorVersion(String currentVersion) {
        // v0.1.0 -> v0.2.0
        String stripped = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;
        String[] parts = stripped.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        return String.format("v%d.%d.%d", major, minor + 1, patch);
    }

    KnowledgeItemResponse toResponseWithLatestVersion(KnowledgeItem item) {
        List<KnowledgeVersion> versions = versionRepository
                .findByKnowledgeItemIdOrderByCreatedAtDesc(item.getId());
        KnowledgeVersion latest = versions.isEmpty() ? null : versions.get(0);
        return toResponse(item, latest);
    }

    private KnowledgeItemResponse toResponse(KnowledgeItem item, KnowledgeVersion version) {
        return KnowledgeItemResponse.builder()
                .id(item.getId())
                .name(item.getName())
                .type(item.getType())
                .description(item.getDescription())
                .currentVersion(item.getCurrentVersion())
                .status(item.getStatus())
                .sensitivity(item.getSensitivity())
                .filePath(item.getFilePath())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .retiredAt(item.getRetiredAt())
                .latestVersion(version != null ? toVersionResponse(version) : null)
                .build();
    }

    private KnowledgeVersionResponse toVersionResponse(KnowledgeVersion version) {
        return KnowledgeVersionResponse.builder()
                .id(version.getId())
                .version(version.getVersion())
                .status(version.getStatus())
                .content(version.getContent())
                .createdAt(version.getCreatedAt())
                .approvedAt(version.getApprovedAt())
                .build();
    }
}
