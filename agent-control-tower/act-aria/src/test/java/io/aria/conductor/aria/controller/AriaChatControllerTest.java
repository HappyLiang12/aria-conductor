package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.dto.AriaAction;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.service.AriaChatAttachmentValidator;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.aria.service.AriaStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        when(ariaService.chat(any(AriaChatRequest.class), any(AriaChatAttachmentValidator.Validated.class)))
                .thenReturn(response);

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
        when(ariaService.chat(any(AriaChatRequest.class), any(AriaChatAttachmentValidator.Validated.class)))
                .thenReturn(AriaChatResponse.builder().message("ok").build());

        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c-2\",\"message\":\"hello\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AriaChatRequest> captor = ArgumentCaptor.forClass(AriaChatRequest.class);
        verify(ariaService).chat(captor.capture(), any(AriaChatAttachmentValidator.Validated.class));
        assertThat(captor.getValue().getConversationId()).isEqualTo("c-2");
        assertThat(captor.getValue().getMessage()).isEqualTo("hello");
    }

    @Test
    void chat_blankMessageIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(ariaService, never()).chat(any(), any());
    }

    @Test
    void chat_missingMessageIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isBadRequest());

        verify(ariaService, never()).chat(any(), any());
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
        verify(ariaStreamService).streamChat(requestCaptor.capture(),
                any(AriaChatAttachmentValidator.Validated.class), emitterCaptor.capture());
        assertThat(emitterCaptor.getValue().getTimeout()).isEqualTo(45_000L);
        assertThat(requestCaptor.getValue().getMessage()).isEqualTo("stream this");
    }

    @Test
    void chatStream_blankMessageIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(ariaStreamService, never()).streamChat(any(), any(), any());
    }

    @Test
    void aStreamWithAnImageIsAcceptedAndPassesTheParts() throws Exception {
        String base64 = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        when(systemConfigService.getLong(eq("aria.sse.timeout.ms"), anyLong(), anyLong(), anyLong()))
                .thenReturn(600_000L);

        MvcResult result = mockMvc.perform(post("/api/v1/aria/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"see this\","
                                + "\"attachments\":[{\"mimeType\":\"image/jpeg\",\"filename\":\"p.jpg\","
                                + "\"dataBase64\":\"" + base64 + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<AriaChatAttachmentValidator.Validated> validated =
                ArgumentCaptor.forClass(AriaChatAttachmentValidator.Validated.class);
        verify(ariaStreamService).streamChat(any(AriaChatRequest.class), validated.capture(), any(SseEmitter.class));
        assertThat(validated.getValue().parts()).hasSize(1);
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    void anUnsupportedAttachmentMimeIs415Json() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"x\",\"attachments\":[{\"mimeType\":\"application/pdf\","
                                + "\"filename\":\"d.pdf\",\"dataBase64\":\"QQ==\"}]}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());
        verifyNoInteractions(ariaStreamService);
    }

    @Test
    void sixAttachmentsAre422Json() throws Exception {
        StringBuilder attachments = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) attachments.append(',');
            attachments.append("{\"mimeType\":\"image/jpeg\",\"filename\":\"x").append(i)
                    .append(".jpg\",\"dataBase64\":\"QQ==\"}");
        }
        attachments.append(']');

        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\",\"attachments\":" + attachments + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());
        verifyNoInteractions(ariaService);
    }
}
