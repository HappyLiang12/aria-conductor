package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.dashboard.report.AmendReportRequest;
import io.aria.conductor.dashboard.report.GenerateReportRequest;
import io.aria.conductor.dashboard.report.ReportService;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportTools implements McpTool {

    private final ReportService reportService;
    private final McpProperties mcpProperties;

    @Tool(name = "generate_report",
            description = "Generate an HTML report from a title and description. Generation is LLM-backed and can take a while; the returned artifact carries id, version and status.")
    public String generateReport(
            @ToolParam(description = "Report title") String title,
            @ToolParam(description = "What the report should contain") String description,
            @ToolParam(description = "Data scope the report covers", required = false) String dataScope,
            @ToolParam(description = "Report owner", required = false) String owner,
            @ToolParam(description = "Source run id this report derives from", required = false) String sourceRunId,
            @ToolParam(description = "Sensitivity label, e.g. internal", required = false) String sensitivity) {
        try {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title is required");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description is required");
            }
            GenerateReportRequest request = GenerateReportRequest.builder()
                    .title(title)
                    .description(description)
                    .dataScope(dataScope)
                    .owner(owner)
                    .sourceRunId(sourceRunId)
                    .sensitivity(sensitivity)
                    .build();
            return ToolResponses.ok(reportService.generate(request));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("REPORT_GENERATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_reports",
            description = "List report artifacts, newest first. Each entry has id, title, version, status and owner.")
    public String listReports() {
        try {
            return ToolResponses.ok(reportService.list());
        } catch (Exception e) {
            return ToolResponses.error("REPORT_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "amend_report",
            description = "Amend an existing report with an instruction, producing a new version. LLM-backed and can take a while.")
    public String amendReport(
            @ToolParam(description = "Report id") String id,
            @ToolParam(description = "Amendment instruction") String instruction) {
        try {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("instruction is required");
            }
            AmendReportRequest request = AmendReportRequest.builder()
                    .instruction(instruction)
                    .build();
            return ToolResponses.ok(reportService.amend(id, request));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("REPORT_AMEND_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
