package io.aria.conductor.execution.context;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;

import java.util.List;

/**
 * Test double for one contributor: either returns its declared sections or
 * throws, so the assembler's ordering, degradation and required-section rules
 * can be exercised without a Spring context.
 */
final class FakeContributor implements ContextContributor {

    private final String id;
    private final int order;
    private final boolean appliesTo;
    private final boolean required;
    private final List<ContextSection> sections;
    private final RuntimeException thrown;

    FakeContributor(String id, int order, boolean appliesTo, boolean required,
                    List<ContextSection> sections, RuntimeException thrown) {
        this.id = id;
        this.order = order;
        this.appliesTo = appliesTo;
        this.required = required;
        this.sections = sections;
        this.thrown = thrown;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return appliesTo;
    }

    @Override
    public boolean required() {
        return required;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (thrown != null) {
            throw thrown;
        }
        return sections;
    }
}
