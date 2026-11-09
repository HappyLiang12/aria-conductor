package io.aria.conductor.memory.fixture;

import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.execution.context.ContextAssembler;

/**
 * Test-only canary for {@code MemoryModuleBoundaryTest}, never production code: the two
 * fields give the real boundary rule one dependency it must reject ({@code act-execution})
 * and one it must allow ({@code act-common}), so a typo in the rule cannot stay green.
 */
public final class MemoryBoundaryFixture {

    private ContextAssembler bannedExecutionDependency;

    private ContextSection allowedCommonDependency;
}
