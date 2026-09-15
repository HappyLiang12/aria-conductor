package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.dashboard.report.AmendReportRequest;
import io.aria.conductor.dashboard.report.GenerateReportRequest;
import io.aria.conductor.dashboard.report.ReportArtifact;
import io.aria.conductor.dashboard.report.ReportService;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportToolsTest {

    @Mock ReportService reportService;
    McpProperties mcpProperties;
    ReportTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ReportTools(reportService, mcpProperties);
    }

    private ReportArtifact artifact(String id, String title, int version, String status) {
        return ReportArtifact.builder()
                .id(id).title(title).version(version).status(status).build();
    }

    @Test
    void generateReport_passesRequestAndWraps() {
        when(reportService.generate(any())).thenReturn(artifact("r-1", "Quarterly review", 1, "GENERATED"));

        String json = tools.generateReport("Quarterly review", "Summarize Q3", "metrics", "alice", null, "internal");

        ArgumentCaptor<GenerateReportRequest> captor = ArgumentCaptor.forClass(GenerateReportRequest.class);
        verify(reportService).generate(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Quarterly review");
        assertThat(captor.getValue().getDescription()).isEqualTo("Summarize Q3");
        assertThat(captor.getValue().getDataScope()).isEqualTo("metrics");
        assertThat(captor.getValue().getOwner()).isEqualTo("alice");
        assertThat(json).contains("\"ok\":true").contains("Quarterly review");
    }

    @Test
    void generateReport_blankTitleIsValidationWithoutStack() {
        String json = tools.generateReport("  ", "desc", null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void listReports_delegates() {
        when(reportService.list()).thenReturn(List.of(artifact("r-1", "Report A", 1, "GENERATED")));

        String json = tools.listReports();

        assertThat(json).contains("\"ok\":true").contains("Report A");
    }

    @Test
    void amendReport_passesInstructionAndWraps() {
        when(reportService.amend(eq("r-1"), any()))
                .thenReturn(artifact("r-1", "Report A", 2, "AMENDED"));

        String json = tools.amendReport("r-1", "add a chart");

        ArgumentCaptor<AmendReportRequest> captor = ArgumentCaptor.forClass(AmendReportRequest.class);
        verify(reportService).amend(eq("r-1"), captor.capture());
        assertThat(captor.getValue().getInstruction()).isEqualTo("add a chart");
        assertThat(json).contains("\"ok\":true").contains("AMENDED");
    }

    @Test
    void amendReport_mapsMissingReportToNotFound() {
        when(reportService.amend(eq("missing"), any()))
                .thenThrow(new ResourceNotFoundException("ReportArtifact", "missing"));

        String json = tools.amendReport("missing", "add a chart");

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void amendReport_missingHtmlIsConflict() {
        when(reportService.amend(eq("r-1"), any()))
                .thenThrow(new IllegalStateException("HTML file missing on disk"));

        String json = tools.amendReport("r-1", "add a chart");

        assertThat(json).contains("\"errorType\":\"CONFLICT\"");
    }

    @Test
    void amendReport_debugOn_includesStack() {
        mcpProperties.setDebug(true);
        when(reportService.amend(eq("r-1"), any()))
                .thenThrow(new IllegalStateException("HTML file missing on disk"));

        String json = tools.amendReport("r-1", "add a chart");

        assertThat(json).contains("stackTrace").contains("IllegalStateException");
    }
}
