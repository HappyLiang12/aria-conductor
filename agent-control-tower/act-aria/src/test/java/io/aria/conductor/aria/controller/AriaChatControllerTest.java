package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.dto.AriaAction;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.aria.service.AriaStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AriaChatControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AriaService ariaService = mock(AriaService.class);
    private final AriaStreamService ariaStreamService = mock(AriaStreamService.class);
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AriaChatController(ariaService, ariaStreamService, systemConfigService)).build();
    }

    @Test
    void chat_returnsResponseBodyFromService() throws Exception {
        AriaChatResponse response = AriaChatResponse.builder()
                .runId("run-1")
                .conversationId("conv-1")
                .message("Here you go")
                .intent("agent.status")
                .actionsTaken(List.of(new AriaAction("list_agents", "list_agents", "3 agents")))
                .timestamp(Instant.now())
                .build();
        when(ariaService.chat(any(AriaChatRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"list agents\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.message").value("Here you go"))
                .andExpect(jsonPath("$.intent").value("agent.status"))
                .andExpect(jsonPath("$.actionsTaken[0].type").value("list_agents"));
    }

    @Test
    void chat_passesDeserializedRequestToService() throws Exception {
        when(ariaService.chat(any(AriaChatRequest.class)))
                .thenReturn(AriaChatResponse.builder().message("ok").build());

        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c-2\",\"message\":\"hello\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AriaChatRequest> captor = ArgumentCaptor.forClass(AriaChatRequest.class);
        verify(ariaService).chat(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo("c-2");
        assertThat(captor.getValue().getMessage()).isEqualTo("hello");
    }

    @Test
    void chat_blankMessageIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(ariaService, never()).chat(any());
    }

    @Test
    void chat_missingMessageIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isBadRequest());

        verify(ariaService, never()).chat(any());
    }

    @Test
    void chatStream_createsEmitterWithConfiguredTimeoutAndDelegates() throws Exception {
        when(systemConfigService.getLong("aria.sse.timeout.ms", 600_000L, 30_000L, 3_600_000L))
                .thenReturn(45_000L);

        mockMvc.perform(post("/api/v1/aria/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"stream this\"}"));

        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        ArgumentCaptor<AriaChatRequest> requestCaptor = ArgumentCaptor.forClass(AriaChatRequest.class);
        verify(ariaStreamService).streamChat(requestCaptor.capture(), emitterCaptor.capture());
        assertThat(emitterCaptor.getValue().getTimeout()).isEqualTo(45_000L);
        assertThat(requestCaptor.getValue().getMessage()).isEqualTo("stream this");
    }

    @Test
    void chatStream_blankMessageIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(ariaStreamService, never()).streamChat(any(), any());
    }
}
