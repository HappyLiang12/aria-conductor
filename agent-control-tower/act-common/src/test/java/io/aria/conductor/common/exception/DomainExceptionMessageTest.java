package io.aria.conductor.common.exception;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Message-building contracts for the domain exceptions. Only the constructors that perform
 * {@code String.format} composition are exercised (plain message pass-through constructors are
 * covered where they participate in the formatting decision).
 */
class DomainExceptionMessageTest {

    @Test
    void resourceNotFound_formatsTypeAndId() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Agent", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(ex).hasMessage("Agent not found with id: 00000000-0000-0000-0000-000000000001");
    }

    @Test
    void resourceNotFound_passThroughConstructorKeepsRawMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("custom message");
        assertThat(ex).hasMessage("custom message");
    }

    @Test
    void resourceNotFound_handlesNullId() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Run", null);
        assertThat(ex).hasMessage("Run not found with id: null");
    }

    @Test
    void invalidStateTransition_formatsEntityAndStates() {
        InvalidStateTransitionException ex =
                new InvalidStateTransitionException("Workflow", "RUNNING", "PENDING");
        assertThat(ex).hasMessage("Invalid state transition for Workflow: RUNNING -> PENDING");
    }

    @Test
    void invalidStateTransition_passThroughConstructorKeepsRawMessage() {
        InvalidStateTransitionException ex = new InvalidStateTransitionException("raw");
        assertThat(ex).hasMessage("raw");
    }

    @Test
    void budgetExceeded_formatsUsedAndLimit() {
        BudgetExceededException ex = new BudgetExceededException(5000L, 4096L);
        assertThat(ex).hasMessage("Token budget exceeded: used 5000, limit 4096");
    }

    @Test
    void budgetExceeded_passThroughConstructorKeepsRawMessage() {
        BudgetExceededException ex = new BudgetExceededException("over budget");
        assertThat(ex).hasMessage("over budget");
    }

    @Test
    void approvalTimeout_formatsApprovalId() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ApprovalTimeoutException ex = new ApprovalTimeoutException(id);
        assertThat(ex).hasMessage("Approval 11111111-1111-1111-1111-111111111111 has timed out");
    }

    @Test
    void approvalTimeout_passThroughConstructorKeepsRawMessage() {
        ApprovalTimeoutException ex = new ApprovalTimeoutException("manual message");
        assertThat(ex).hasMessage("manual message");
    }

    @Test
    void allDomainExceptions_areRuntimeExceptions() {
        assertThat(new ResourceNotFoundException("x")).isInstanceOf(RuntimeException.class);
        assertThat(new InvalidStateTransitionException("x")).isInstanceOf(RuntimeException.class);
        assertThat(new BudgetExceededException("x")).isInstanceOf(RuntimeException.class);
        assertThat(new ApprovalTimeoutException("x")).isInstanceOf(RuntimeException.class);
    }
}
