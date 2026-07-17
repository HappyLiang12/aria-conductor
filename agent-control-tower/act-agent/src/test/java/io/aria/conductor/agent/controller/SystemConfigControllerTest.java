package io.aria.conductor.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.common.model.SystemConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemConfigControllerTest {

    private SystemConfigService service;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(SystemConfigService.class);
        SystemConfigController controller = new SystemConfigController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private SystemConfig config(String key, String value) {
        return SystemConfig.builder()
                .configKey(key)
                .configValue(value)
                .description("test desc")
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void listAll_returnsAllConfigs() throws Exception {
        when(service.listAll()).thenReturn(List.of(
                config("a", "1"),
                config("b", "2")
        ));

        mockMvc.perform(get("/api/v1/system-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].configKey").value("a"))
                .andExpect(jsonPath("$[1].configKey").value("b"));
    }

    @Test
    void getByKey_returnsConfig() throws Exception {
        when(service.getByKey("my.key")).thenReturn(config("my.key", "42"));

        mockMvc.perform(get("/api/v1/system-config/my.key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configKey").value("my.key"))
                .andExpect(jsonPath("$.configValue").value("42"));
    }

    @Test
    void getByKey_returns404_whenMissing() throws Exception {
        when(service.getByKey("nope")).thenThrow(new IllegalArgumentException("Unknown config key: nope"));

        mockMvc.perform(get("/api/v1/system-config/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateValue_updatesAndReturns() throws Exception {
        SystemConfig updated = config("my.key", "120");
        when(service.updateValue(eq("my.key"), eq("120"))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/system-config/my.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("value", "120"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configValue").value("120"));
    }

    @Test
    void updateValue_returns400_whenValueBlank() throws Exception {
        mockMvc.perform(put("/api/v1/system-config/my.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("value", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateValue_returns400_whenValueMissing() throws Exception {
        mockMvc.perform(put("/api/v1/system-config/my.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("other", "x"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateValue_returns404_whenKeyUnknown() throws Exception {
        when(service.updateValue(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Unknown config key"));

        mockMvc.perform(put("/api/v1/system-config/bad.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("value", "10"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void resetToDefault_returnsConfig() throws Exception {
        when(service.getByKey("my.key")).thenReturn(config("my.key", "600"));

        mockMvc.perform(post("/api/v1/system-config/my.key/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configKey").value("my.key"));
    }

    @Test
    void resetToDefault_returns404_whenMissing() throws Exception {
        when(service.getByKey("nope")).thenThrow(new IllegalArgumentException("Unknown"));

        mockMvc.perform(post("/api/v1/system-config/nope/reset"))
                .andExpect(status().isNotFound());
    }
}
