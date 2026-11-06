package io.aria.conductor.knowledge.controller;

import io.aria.conductor.knowledge.sdd.SpecReviewCoordinator;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SddWorkflowControllerTest extends WebMvcTestBase {

    private final SpecReviewCoordinator coordinator = mock(SpecReviewCoordinator.class);
    private final MockMvc mvc = mockMvcFor(new SddWorkflowController(coordinator));

    @Test
    void resubmitApproval_chainNotWaiting_returns400WithBody() throws Exception {
        UUID chainId = UUID.randomUUID();
        when(coordinator.resubmitApproval(chainId))
                .thenThrow(new IllegalStateException("Chain " + chainId
                        + " is COMPLETED; resubmit requires WAITING_APPROVAL"));

        mvc.perform(post("/api/v1/workflows/" + chainId + "/resubmit-approval"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("WAITING_APPROVAL")));
    }
}
