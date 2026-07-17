package io.aria.conductor.common.port;

import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;

import java.util.UUID;

/**
 * Port interface for capturing workflow chains as knowledge items.
 * <p>
 * Implemented by act-knowledge; consumed by act-execution.
 * Lives in act-common to avoid circular module dependencies.
 * </p>
 */
public interface KnowledgeCapturePort {

    /**
     * Submit a new knowledge item with both Markdown and YAML content.
     *
     * @param name        knowledge item name
     * @param type        knowledge type (e.g. WORKFLOW)
     * @param description human-readable description
     * @param mdContent   Markdown content body
     * @param yamlContent YAML template content (may be {@code null})
     * @param sensitivity sensitivity level
     * @return the newly created knowledge item ID
     */
    UUID submitKnowledge(String name,
                         KnowledgeType type,
                         String description,
                         String mdContent,
                         String yamlContent,
                         Sensitivity sensitivity);

    /**
     * Capture a completed workflow chain as a knowledge item.
     * Generates Markdown and YAML from the chain's steps, submits the knowledge item,
     * and returns its ID.
     *
     * @param chainId the ID of the completed workflow chain
     * @return the newly created knowledge item ID, or {@code null} if capture was skipped
     */
    UUID captureWorkflowChain(UUID chainId);
}
