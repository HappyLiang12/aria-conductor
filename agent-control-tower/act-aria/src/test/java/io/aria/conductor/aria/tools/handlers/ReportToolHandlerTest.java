package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.dashboard.report.AmendReportRequest;
import io.aria.conductor.dashboard.report.GenerateReportRequest;
import io.aria.conductor.dashboard.report.ReportArtifact;
import io.aria.conductor.dashboard.report.ReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportToolHandlerTest {

    @Mock
    private ReportService reportService;

    @InjectMocks
    private ReportToolHandler handler;

    @Test
    void generateReportShouldReturnMetadata() {
        ReportArtifact artifact = ReportArtifact.builder()
                .id("rep-1")
                .title("Sprint Health")
                .version(1)
                .status("GENERATED")
                .owner("ops")
                .dataScope("last-7d")
                .createdAt(Instant.now())
                .build();
        when(reportService.generate(any(GenerateReportRequest.class))).thenReturn(artifact);

        String result = handler.execute(Map.of(
                "toolName", "generate_report",
                "title", "Sprint Health",
                "description", "Summarize this sprint",
                "dataScope", "last-7d",
                "owner", "ops"
        ));

        assertTrue(result.contains("rep-1"));

        ArgumentCaptor<GenerateReportRequest> captor = ArgumentCaptor.forClass(GenerateReportRequest.class);
        verify(reportService).generate(captor.capture());
        assertEquals("Sprint Health", captor.getValue().getTitle());
        assertEquals("last-7d", captor.getValue().getDataScope());
    }

    @Test
    void generateReportMissingTitleShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "generate_report",
                "description", "desc"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(reportService);
    }

    @Test
    void generateReportMissingDescriptionShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "generate_report",
                "title", "T"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(reportService);
    }

    @Test
    void listReportsShouldReturnMetadataArray() {
        ReportArtifact a = ReportArtifact.builder()
                .id("rep-1").title("A").version(1).status("GENERATED").createdAt(Instant.now()).build();
        ReportArtifact b = ReportArtifact.builder()
                .id("rep-2").title("B").version(2).status("AMENDED").createdAt(Instant.now()).build();
        when(reportService.list()).thenReturn(List.of(a, b));

        String result = handler.execute(Map.of("toolName", "list_reports"));

        assertTrue(result.contains("rep-1"));
        assertTrue(result.contains("rep-2"));
        assertTrue(result.contains("AMENDED"));
        verify(reportService).list();
    }

    @Test
    void amendReportShouldReturnUpdatedMetadata() {
        ReportArtifact amended = ReportArtifact.builder()
                .id("rep-1")
                .title("Sprint Health")
                .version(2)
                .status("AMENDED")
                .createdAt(Instant.now())
                .build();
        when(reportService.amend(eq("rep-1"), any(AmendReportRequest.class))).thenReturn(amended);

        String result = handler.execute(Map.of(
                "toolName", "amend_report",
                "id", "rep-1",
                "amendment", "Add a summary section"
        ));

        assertTrue(result.contains("version: 2"));

        ArgumentCaptor<AmendReportRequest> captor = ArgumentCaptor.forClass(AmendReportRequest.class);
        verify(reportService).amend(eq("rep-1"), captor.capture());
        assertEquals("Add a summary section", captor.getValue().getInstruction());
    }

    @Test
    void amendReportMissingIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "amend_report",
                "instruction", "Update"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(reportService);
    }

    @Test
    void amendReportMissingInstructionShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "amend_report",
                "id", "rep-1"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(reportService);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
