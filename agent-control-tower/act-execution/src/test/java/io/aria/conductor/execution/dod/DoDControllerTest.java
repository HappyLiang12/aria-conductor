package io.aria.conductor.execution.dod;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.dod.dto.CreateEvidenceRequest;
import io.aria.conductor.execution.dod.dto.DoDStatusResponse;
import io.aria.conductor.execution.dod.dto.InitDoDRequest;
import io.aria.conductor.execution.dod.dto.SubmitReviewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DoDControllerTest {

    private DoDService dodService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dodService = mock(DoDService.class);
        DoDController controller = new DoDController(dodService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void postInit_returns200WithRecord() throws Exception {
        DoDRecord record = DoDRecord.builder()
                .id("d1").taskId("T1").currentStage("dev")
                .overallStatus(DoDService.STATUS_IN_PROGRESS).build();
        when(dodService.init("T1", "story")).thenReturn(record);

        InitDoDRequest body = new InitDoDRequest("T1", "story");
        mockMvc.perform(post("/api/v1/dod/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.taskId").value("T1"))
                .andExpect(jsonPath("$.currentStage").value("dev"));
    }

    @Test
    void postReview_returns200AndAdvancesStage() throws Exception {
        DoDRecord advanced = DoDRecord.builder()
                .id("d1").taskId("T1").currentStage("qa")
                .overallStatus(DoDService.STATUS_IN_PROGRESS).build();
        when(dodService.review(eq("T1"), eq("u1"), any(), eq(true), any(), any()))
                .thenReturn(advanced);

        SubmitReviewRequest body = new SubmitReviewRequest("T1", "u1", "Alice", true, "evidence", "lgtm");
        mockMvc.perform(post("/api/v1/dod/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("qa"));
    }

    @Test
    void postReview_invalidState_returns400() throws Exception {
        when(dodService.review(any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new IllegalStateException("DoD already completed"));

        SubmitReviewRequest body = new SubmitReviewRequest("T1", "u1", null, true, null, null);
        mockMvc.perform(post("/api/v1/dod/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DoD already completed"));
    }

    @Test
    void getStatus_returns200WithFullStatus() throws Exception {
        DoDRecord record = DoDRecord.builder()
                .id("d1").taskId("T1").currentStage("qa")
                .overallStatus(DoDService.STATUS_IN_PROGRESS).build();
        DoDStatusResponse response = DoDStatusResponse.of(
                record,
                List.of(new DoDStatusResponse.StageStatus("dev", true, "PASSED", 1, null),
                        new DoDStatusResponse.StageStatus("qa", true, "PENDING", 0, null),
                        new DoDStatusResponse.StageStatus("ba", false, "PENDING", 0, null),
                        new DoDStatusResponse.StageStatus("pm", false, "PENDING", 0, null)),
                List.of(),
                3L);
        when(dodService.buildStatusResponse("T1")).thenReturn(response);

        mockMvc.perform(get("/api/v1/dod/T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("T1"))
                .andExpect(jsonPath("$.currentStage").value("qa"))
                .andExpect(jsonPath("$.stages.length()").value(4))
                .andExpect(jsonPath("$.stages[0].stage").value("dev"))
                .andExpect(jsonPath("$.stages[0].status").value("PASSED"))
                .andExpect(jsonPath("$.evidenceCount").value(3));
    }

    @Test
    void getStatus_unknownTask_returns404() throws Exception {
        when(dodService.buildStatusResponse("nope"))
                .thenThrow(new IllegalStateException("No DoD record"));

        mockMvc.perform(get("/api/v1/dod/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getEvidence_returns200WithList() throws Exception {
        EvidenceItem item = EvidenceItem.builder()
                .id("e1").dodId("d1").taskId("T1").type("LOG").title("Build log").build();
        when(dodService.getEvidence("T1")).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/dod/T1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("LOG"))
                .andExpect(jsonPath("$[0].title").value("Build log"));
    }

    @Test
    void postEvidence_returns200WithSavedItem() throws Exception {
        EvidenceItem item = EvidenceItem.builder()
                .id("e1").dodId("d1").taskId("T1").type("LOG").title("Run log").build();
        when(dodService.addEvidence(eq("T1"), any(CreateEvidenceRequest.class))).thenReturn(item);

        CreateEvidenceRequest body = new CreateEvidenceRequest("LOG", "Run log", "txt", null, "run-1");
        mockMvc.perform(post("/api/v1/dod/T1/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("e1"))
                .andExpect(jsonPath("$.title").value("Run log"));
    }
}
