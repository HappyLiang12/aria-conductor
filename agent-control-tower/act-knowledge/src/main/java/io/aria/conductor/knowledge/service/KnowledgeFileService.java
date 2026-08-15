package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.KnowledgeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Service
public class KnowledgeFileService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileService.class);

    private final Path basePath;

    public KnowledgeFileService(@Value("${knowledge.storage.base-path:./data/knowledge}") String basePath) {
        this.basePath = Paths.get(basePath);
        log.info("Knowledge file storage configured at: {}", this.basePath.toAbsolutePath());
    }

    public String storeContent(KnowledgeType type, String name, String version, String content) {
        Path filePath = resolveFilePath(type, name, version);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.debug("Stored knowledge content at: {}", filePath);
            return filePath.toString();
        } catch (IOException e) {
            log.error("Failed to store knowledge content at: {}", filePath, e);
            throw new RuntimeException("Failed to store knowledge content", e);
        }
    }

    public Optional<String> readContent(KnowledgeType type, String name, String version) {
        Path filePath = resolveFilePath(type, name, version);
        if (!Files.exists(filePath)) {
            log.warn("Knowledge content file not found: {}", filePath);
            return Optional.empty();
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (IOException e) {
            log.error("Failed to read knowledge content from: {}", filePath, e);
            return Optional.empty();
        }
    }

    public void deleteContent(KnowledgeType type, String name, String version) {
        Path filePath = resolveFilePath(type, name, version);
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.debug("Deleted knowledge content at: {}", filePath);
            }
        } catch (IOException e) {
            log.error("Failed to delete knowledge content at: {}", filePath, e);
        }
    }

    private Path resolveFilePath(KnowledgeType type, String name, String version) {
        String extension = getExtension(type);
        return basePath.resolve(type.name().toLowerCase())
                .resolve(name)
                .resolve(version + extension);
    }

    private String getExtension(KnowledgeType type) {
        return switch (type) {
            case SKILL -> ".yaml";
            case SCRIPT -> ".py";
            case PROMPT -> ".md";
            case TOOL -> ".yaml";
            case TEMPLATE -> ".md";
            case GUIDELINE -> ".md";
            case WORKFLOW -> ".md";
            case SPEC -> ".md";
        };
    }
}
