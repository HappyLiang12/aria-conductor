package io.aria.conductor.memory.dto;

/**
 * The operator's verdict on a PENDING proposal. {@code APPROVE} promotes the subject to
 * ACTIVE and retires any conflicting current value; {@code REJECT} retires the proposal so
 * it can never become ACTIVE.
 */
public enum ProposalDecision {
    APPROVE,
    REJECT
}
