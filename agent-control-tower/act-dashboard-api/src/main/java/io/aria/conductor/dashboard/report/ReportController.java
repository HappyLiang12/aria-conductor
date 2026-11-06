package io.aria.conductor.dashboard.report;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/generate")
    public ResponseEntity<ReportResponse> generate(@Valid @RequestBody GenerateReportRequest request) {
        ReportArtifact artifact = reportService.generate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportResponse.from(artifact));
    }

    @GetMapping
    public ResponseEntity<List<ReportResponse>> list() {
        List<ReportResponse> responses = reportService.list().stream()
                .map(ReportResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReportResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(ReportResponse.from(reportService.get(id)));
    }

    @GetMapping(value = "/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable String id) {
        String html = reportService.getHtml(id);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping("/{id}/amend")
    public ResponseEntity<ReportResponse> amend(@PathVariable String id,
                                                @Valid @RequestBody AmendReportRequest request) {
        ReportArtifact artifact = reportService.amend(id, request);
        return ResponseEntity.ok(ReportResponse.from(artifact));
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<ReportResponse> regenerate(@PathVariable String id) {
        ReportArtifact artifact = reportService.regenerate(id);
        return ResponseEntity.ok(ReportResponse.from(artifact));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable String id) {
        reportService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
