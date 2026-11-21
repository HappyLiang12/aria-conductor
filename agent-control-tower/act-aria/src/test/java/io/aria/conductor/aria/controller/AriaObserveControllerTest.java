package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.service.AriaObservationValidator;
import io.aria.conductor.aria.service.AriaObserveService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AriaObserveControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AriaObserveService service = mock(AriaObserveService.class);
    private final OperatorAuthorityResolver authority = mock(OperatorAuthorityResolver.class);
    private final SystemConfigService systemConfig = mock(SystemConfigService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AriaObserveController(service, authority, systemConfig)).build();
        when(systemConfig.getLong(eq("aria.sse.timeout.ms"), anyLong(), anyLong(), anyLong()))
                .thenReturn(600_000L);
        when(service.preflight(any())).thenReturn(Optional.empty());
    }

    @Test
    void aWorkerCredentialIs403() throws Exception {
        when(authority.resolveOperator(any(), eq(true)))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException("worker"));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("worker"));
    }

    @Test
    void noVerifiableIdentityIs401() throws Exception {
        when(authority.resolveOperator(any(), eq(true)))
                .thenThrow(new SecurityException("operator session required"));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnsupportedMimeIs415() throws Exception {
        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/gif", "/9j/2Q==")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aCoreRefusalIs409() throws Exception {
        when(service.preflight(any())).thenReturn(Optional.of(
                new AriaObservationValidator.Rejection(HttpStatus.CONFLICT, "opencode only")));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("opencode only"));
    }

    @Test
    void aWorkerCredentialIs403EvenWhenTheBodyWouldFailValidation() throws Exception {
        when(authority.resolveOperator(any(), eq(true)))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException("worker"));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/gif", "/9j/2Q==")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("worker"));

        verify(service, never()).preflight(any());
    }

    @Test
    void anUnsupportedMimeIs415EvenWhenTheCoreWouldRefuse() throws Exception {
        when(service.preflight(any())).thenReturn(Optional.of(
                new AriaObservationValidator.Rejection(HttpStatus.CONFLICT, "opencode only")));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/gif", "/9j/2Q==")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("image.mimeType must be image/jpeg or image/png"));
    }

    @Test
    void aRefusalStaysJsonWhenTheClientAsksForTheStream() throws Exception {
        when(authority.resolveOperator(any(), eq(true)))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException("worker"));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("worker"));
    }

    @Test
    void aValidObservationStartsTheStream() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isOk())
                .andReturn();

        verify(service).stream(any(), any(), any());
        result.getRequest().getAsyncContext().complete();
    }

    private String body(String mime, String base64) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "conversationId", "conv-1",
                "image", Map.of("mimeType", mime, "dataBase64", base64),
                "clientCapabilities", Map.of("kind", "glasses", "ar", true),
                "observation", Map.of(
                        "timestamp", 1760000000000L,
                        "objects", java.util.List.of(Map.of(
                                "id", "o1", "trackId", 7, "label", "remote",
                                "bbox", java.util.List.of(0.4, 0.5, 0.1, 0.1))))));
    }
}
