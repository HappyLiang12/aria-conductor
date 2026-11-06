package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP slice test for the inject-message endpoint. Complements the existing
 * {@code RunToolCallControllerTest} (direct method calls) by exercising the
 * {@code @Valid InjectMessageRequest} bean validation and JSON mapping, which
 * only trigger on the MockMvc path.
 */
class RunToolCallControllerWebTest extends WebMvcTestBase {

    private final ToolCallRepository toolCallRepository = mock(ToolCallRepository.class);
    private final SessionTrajectoryRepository trajectoryRepository = mock(SessionTrajectoryRepository.class);
    private final MockMvc mvc = mockMvcFor(
            new RunToolCallController(toolCallRepository, trajectoryRepository));

    private void stubSaveEcho() {
        when(trajectoryRepository.save(any(SessionTrajectory.class))).thenAnswer(inv -> {
            SessionTrajectory t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            t.setCreatedAt(Instant.now());
            return t;
        });
    }

    @Test
    void injectMessage_appendsEntryAtNextTurnWithGivenRole() throws Exception {
        UUID runId = UUID.randomUUID();
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(4);
        stubSaveEcho();

        mvc.perform(post("/api/v1/runs/" + runId + "/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "Please stop and re-plan", "role", "human"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.turnNumber").value(5))
                .andExpect(jsonPath("$.role").value("human"))
                .andExpect(jsonPath("$.content").value("Please stop and re-plan"));

        ArgumentCaptor<SessionTrajectory> saved = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository).save(saved.capture());
        assertThat(saved.getValue().getRunId()).isEqualTo(runId);
        assertThat(saved.getValue().getTurnNumber()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"content\":\"hi\"}",                    // role absent
            "{\"content\":\"hi\",\"role\":\"   \"}"    // role blank
    })
    void injectMessage_missingOrBlankRole_defaultsToUser(String body) throws Exception {
        UUID runId = UUID.randomUUID();
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(0);
        stubSaveEcho();

        mvc.perform(post("/api/v1/runs/" + runId + "/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.turnNumber").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",                          // content absent
            "{\"content\":\"\"}",          // empty
            "{\"content\":\"   \"}"        // blank
    })
    void injectMessage_blankContent_returns400WithoutPersisting(String body) throws Exception {
        mvc.perform(post("/api/v1/runs/" + UUID.randomUUID() + "/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("content")));
        verify(trajectoryRepository, never()).save(any());
    }
}
