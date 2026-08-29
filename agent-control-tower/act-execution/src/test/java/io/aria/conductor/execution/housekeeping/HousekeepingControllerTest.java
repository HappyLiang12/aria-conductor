package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Housekeeping S4: REST surface — read-only scan and confirm-gated execute.
 */
class HousekeepingControllerTest {

    private HousekeepingService service;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment mockEnv = mock(Environment.class);
    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @BeforeEach
    void setUp() {
        service = mock(HousekeepingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HousekeepingController(service))
                .setControllerAdvice(new GlobalExceptionHandler(mockEnv))
                .build();
    }

    @Test
    void scan_returnsCategoryShape() throws Exception {
        when(service.scan(anyBoolean(), any()))
                .thenReturn(new ScanResult(List.of(
                        new CategorySummary("runs", 2, List.of()),
                        new CategorySummary("stuck", 0, List.of())), Instant.now()));

        mockMvc.perform(get("/api/v1/housekeeping/scan").param("includeStuck", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].key").value("runs"))
                .andExpect(jsonPath("$.categories[0].count").value(2))
                .andExpect(jsonPath("$.categories[1].key").value("stuck"));
    }

    @Test
    void execute_withoutConfirm_returns400_andNoSideEffects() throws Exception {
        String body = objectMapper.writeValueAsString(new HousekeepingModel.HousekeepingRequest(
                List.of("kanban"), false, Exclusions.empty(), false));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).execute(any());
    }

    @Test
    void execute_withConfirm_returnsReceipt() throws Exception {
        when(service.execute(any())).thenReturn(new HousekeepingReceipt(
                List.of(new CategoryReceipt("kanban", 3, 0, 1)), Instant.now()));
        String body = objectMapper.writeValueAsString(new HousekeepingModel.HousekeepingRequest(
                List.of("kanban"), false, Exclusions.empty(), true));

        mockMvc.perform(post("/api/v1/housekeeping/execute")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].key").value("kanban"))
                .andExpect(jsonPath("$.categories[0].cleared").value(3));
    }
}
