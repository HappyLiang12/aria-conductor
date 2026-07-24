package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.service.ScheduledJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Complements ScheduledJobControllerTest with the update endpoint and
 * query-parameter pass-through for list filtering.
 */
class ScheduledJobControllerEdgeCasesTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledJobService scheduledJobService = mock(ScheduledJobService.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduledJobController(scheduledJobService))
                .build();
    }

    @Test
    void update_passesIdAndBodyToServiceAndReturnsUpdatedJob() throws Exception {
        ScheduledJobDto input = new ScheduledJobDto(null, "RECURRING", "REMINDER", "Renamed",
                "7200", "Notify me", null, null, null, null, null, null);
        ScheduledJobDto output = new ScheduledJobDto("j1", "RECURRING", "REMINDER", "Renamed",
                "7200", "Notify me", null, null, null, "ACTIVE", null, null);
        when(scheduledJobService.update(eq("j1"), any())).thenReturn(output);

        mockMvc.perform(put("/api/v1/aria/jobs/j1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("j1"))
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ArgumentCaptor<ScheduledJobDto> captor = ArgumentCaptor.forClass(ScheduledJobDto.class);
        verify(scheduledJobService).update(eq("j1"), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("Renamed");
    }

    @Test
    void list_passesCategoryAndStatusFiltersThrough() throws Exception {
        ScheduledJobDto dto = new ScheduledJobDto("j2", "RECURRING", "REMINDER", "Job 2",
                "60", "N", null, null, null, "PAUSED", null, null);
        when(scheduledJobService.list("REMINDER", "PAUSED")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/aria/jobs")
                        .param("category", "REMINDER")
                        .param("status", "PAUSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("j2"))
                .andExpect(jsonPath("$[0].status").value("PAUSED"));

        verify(scheduledJobService).list("REMINDER", "PAUSED");
    }

    @Test
    void list_returnsEmptyArrayWhenNoJobsMatch() throws Exception {
        when(scheduledJobService.list("REPORT", null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/jobs").param("category", "REPORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
