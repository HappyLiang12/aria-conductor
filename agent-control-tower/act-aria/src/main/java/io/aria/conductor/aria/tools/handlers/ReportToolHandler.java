package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.dashboard.report.AmendReportRequest;
import io.aria.conductor.dashboard.report.GenerateReportRequest;
import io.aria.conductor.dashboard.report.ReportArtifact;
import io.aria.conductor.dashboard.report.ReportService;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("reportToolHandler")
public class ReportToolHandler implements ToolHandler {

    private final ReportService reportService;

    public ReportToolHandler(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "generate_report" -> generateReport(arguments);
                case "list_reports" -> listReports();
                case "amend_report" -> amendReport(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("ReportToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String generateReport(Map<String, Object> args) {
        String title = Objects.toString(args.get("title"), "");
        String description = Objects.toString(args.get("description"), "");
        if (title.isEmpty()) return error("Missing required parameter: title");
        if (description.isEmpty()) return error("Missing required parameter: description");

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title(title)
                .description(description)
                .dataScope(blankToNull(Objects.toString(args.get("dataScope"), "")))
                .owner(blankToNull(Objects.toString(args.get("owner"), "")))
                .sourceRunId(blankToNull(Objects.toString(args.get("sourceRunId"), "")))
                .sensitivity(blankToNull(Objects.toString(args.get("sensitivity"), "")))
                .build();
        ReportArtifact artifact = reportService.generate(request);
        return "Report '" + artifact.getTitle() + "' generated (id: " + artifact.getId() + ", version: " + artifact.getVersion() + ")";
    }

    private String listReports() {
        List<ReportArtifact> reports = reportService.list();
        if (reports.isEmpty()) return "No reports found.";
        StringBuilder sb = new StringBuilder("Reports (" + reports.size() + " total):\n");
        for (ReportArtifact r : reports) {
            sb.append("  - ").append(r.getId())
                    .append(" | ").append(r.getTitle())
                    .append(" | Version: ").append(r.getVersion())
                    .append(" | Status: ").append(r.getStatus())
                    .append(" | Owner: ").append(r.getOwner() != null ? r.getOwner() : "N/A")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String amendReport(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        String amendment = Objects.toString(args.get("amendment"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        if (amendment.isEmpty()) return error("Missing required parameter: amendment");

        AmendReportRequest request = AmendReportRequest.builder()
                .instruction(amendment)
                .build();
        ReportArtifact artifact = reportService.amend(id, request);
        return "Report '" + artifact.getTitle() + "' amended (id: " + artifact.getId() + ", version: " + artifact.getVersion() + ")";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
