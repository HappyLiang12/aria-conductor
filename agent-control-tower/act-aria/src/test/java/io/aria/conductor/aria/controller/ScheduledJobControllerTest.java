package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.service.ScheduledJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScheduledJobControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledJobService scheduledJobService = mock(ScheduledJobService.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduledJobController(scheduledJobService)).build();
    }

    @Test
    void list_returnsJobs() throws Exception {
        ScheduledJobDto dto = new ScheduledJobDto("j1", "RECURRING", "REMINDER", "Job 1",
                "3600", "Notify", null, null, null, "ACTIVE", null, null);
        when(scheduledJobService.list(null, null)).thenReturn(List.of(dto));
        mockMvc.perform(get("/api/v1/aria/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("j1"));
    }

    @Test
    void create_returnsCreatedJob() throws Exception {
        ScheduledJobDto input = new ScheduledJobDto(null, "RECURRING", "REMINDER", "T",
                "60", "N", null, null, null, null, null, null);
        ScheduledJobDto output = new ScheduledJobDto("j1", "RECURRING", "REMINDER", "T",
                "60", "N", null, null, null, "ACTIVE", null, null);
        when(scheduledJobService.create(any())).thenReturn(output);

        mockMvc.perform(post("/api/v1/aria/jobs").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("j1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/aria/jobs/j1")).andExpect(status().isNoContent());
        verify(scheduledJobService).delete("j1");
    }

    @Test
    void pause_returnsPausedJob() throws Exception {
        ScheduledJobDto dto = new ScheduledJobDto("j1", null, null, null, null, null,
                null, null, null, "PAUSED", null, null);
        when(scheduledJobService.pause("j1")).thenReturn(dto);
        mockMvc.perform(patch("/api/v1/aria/jobs/j1/pause")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void resume_returnsActiveJob() throws Exception {
        ScheduledJobDto dto = new ScheduledJobDto("j1", null, null, null, null, null,
                null, null, null, "ACTIVE", null, null);
        when(scheduledJobService.resume("j1")).thenReturn(dto);
        mockMvc.perform(patch("/api/v1/aria/jobs/j1/resume")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
