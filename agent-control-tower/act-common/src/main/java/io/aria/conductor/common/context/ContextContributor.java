package io.aria.conductor.common.context;

import java.util.List;

/**
 * Produces typed sections of the context envelope (spec §8.1). Contributors are
 * ordered; an optional contributor that does not apply may return no sections,
 * while a missing required section fails the run.
 */
public interface ContextContributor {

    String id();

    int order();

    default boolean appliesTo(ContextRequest request) {
        return true;
    }

    default boolean required() {
        return false;
    }

    List<ContextSection> contribute(ContextRequest request);
}
