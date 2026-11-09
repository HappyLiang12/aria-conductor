package io.aria.conductor.dashboard.memory.dto;

import io.aria.conductor.memory.dto.ProposalDecision;

/** Request body of {@code POST /api/v1/memory/proposals/{id}/decide}. */
public record DecideProposalRequest(ProposalDecision decision) {
}
