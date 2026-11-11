package io.aria.conductor.knowledge.context;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Contributes approved knowledge to the context envelope (spec §8.1 "Knowledge",
 * P3/P4/P6). One section per item; the service owns the bounded query and the
 * sensitivity filter. The section is optional — empty knowledge simply yields no
 * sections.
 */
@Component
public class KnowledgeContextContributor implements ContextContributor {

    static final int ORDER = 50;

    /** P6: top 5 approved items, the same bound the engine used before the pipeline. */
    static final int LIMIT = 5;

    private final KnowledgeService knowledgeService;

    public KnowledgeContextContributor(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String id() {
        return "knowledge";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        return knowledgeService.buildKnowledgeSections(LIMIT, request.maxSensitivity());
    }
}
