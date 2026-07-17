package io.aria.conductor.dashboard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.env.Environment;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportControllerTest {

    private ReportService reportService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment mockEnv = mock(Environment.class);
    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        ReportController controller = new ReportController(reportService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mockEnv))
                .build();
    }

    private ReportArtifact sample(String id, int version, String status) {
        return ReportArtifact.builder()
                .id(id)
                .title("Sample")
                .version(version)
                .status(status)
                .sensitivity("internal")
                .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .htmlPath("/tmp/" + id + ".html")
                .build();
    }

    @Test
    void generate_returns201_withReportMetadata() throws Exception {
        ReportArtifact created = sample("rpt-1", 1, "GENERATED");
        when(reportService.generate(any(GenerateReportRequest.class))).thenReturn(created);

        GenerateReportRequest body = GenerateReportRequest.builder()
                .title("Sample")
                .description("Summarize last week")
                .build();

        mockMvc.perform(post("/api/v1/reports/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("rpt-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.htmlUrl").value("/api/v1/reports/rpt-1/html"));
    }

    @Test
    void list_returns200_withReports() throws Exception {
        when(reportService.list()).thenReturn(List.of(
                sample("a", 1, "GENERATED"),
                sample("b", 2, "AMENDED")
        ));

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("a"))
                .andExpect(jsonPath("$[1].status").value("AMENDED"));
    }

    @Test
    void getHtml_returns200_withTextHtmlContentType() throws Exception {
        when(reportService.getHtml("rpt-1"))
                .thenReturn("<!DOCTYPE html><html><body>hello</body></html>");

        mockMvc.perform(get("/api/v1/reports/rpt-1/html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<!DOCTYPE html>")));
    }

    @Test
    void amend_returns200_withUpdatedVersion() throws Exception {
        ReportArtifact updated = sample("rpt-1", 2, "AMENDED");
        updated.setAmendedAt(Instant.parse("2025-01-02T00:00:00Z"));
        when(reportService.amend(eq("rpt-1"), any(AmendReportRequest.class))).thenReturn(updated);

        AmendReportRequest body = AmendReportRequest.builder()
                .instruction("Add critical blockers section")
                .build();

        mockMvc.perform(post("/api/v1/reports/rpt-1/amend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("AMENDED"));
    }

    @Test
    void get_missing_returns404() throws Exception {
        when(reportService.get("missing"))
                .thenThrow(new ResourceNotFoundException("ReportArtifact", "missing"));

        mockMvc.perform(get("/api/v1/reports/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void regenerate_returns200() throws Exception {
        ReportArtifact updated = sample("rpt-1", 2, "AMENDED");
        when(reportService.regenerate("rpt-1")).thenReturn(updated);

        mockMvc.perform(post("/api/v1/reports/rpt-1/regenerate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/reports/rpt-1"))
                .andExpect(status().isNoContent());

        verify(reportService).archive("rpt-1");
    }
}
