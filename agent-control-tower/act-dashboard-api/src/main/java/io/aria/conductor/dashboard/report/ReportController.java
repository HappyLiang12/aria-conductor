package io.aria.conductor.dashboard.report;

import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Map;

/**
 * Report REST surface. {@code POST /{id}/regenerate} and {@code DELETE /{id}} are
 * operator decisions and resolve operator authority before the service call; the
 * three reads stay open.
 *
 * <p>{@code POST /generate} and {@code POST /{id}/amend} are deliberately NOT gated:
 * both are {@code WORKER_WRITE} in the MCP tool catalog ({@code generate_report},
 * {@code amend_report}) and the QA workflow instructs a run-scoped worker to generate
 * its report, so gating them would refuse a legitimate worker path.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final OperatorAuthorityResolver operatorAuthority;

    public ReportController(ReportService reportService,
                            OperatorAuthorityResolver operatorAuthority) {
        this.reportService = reportService;
        this.operatorAuthority = operatorAuthority;
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

    /** Operator-only: regenerating a report replaces the artifact the operator reads. */
    @PostMapping("/{id}/regenerate")
    public ResponseEntity<?> regenerate(@PathVariable String id, HttpServletRequest request) {
        ResponseEntity<Object> refusal = rejectNonOperator(request,
                "regenerating a report", "Regenerate it on the Reports page.");
        if (refusal != null) {
            return refusal;
        }
        ReportArtifact artifact = reportService.regenerate(id);
        return ResponseEntity.ok(ReportResponse.from(artifact));
    }

    /** Operator-only: archiving a report retires the artifact from the list. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> archive(@PathVariable String id, HttpServletRequest request) {
        ResponseEntity<Object> refusal = rejectNonOperator(request,
                "archiving a report", "Archive it on the Reports page.");
        if (refusal != null) {
            return refusal;
        }
        reportService.archive(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The controller idiom of {@code AgentController.retire} and
     * {@code LlmProviderController}: 403 for a worker credential or a forged cookie
     * mutation, 401 with no verifiable identity. Null means the caller is the operator;
     * call it before any side effect.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, String action,
                                                     String surface) {
        try {
            operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", refusal(action, surface)));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", refusal(action, surface)));
        }
        return null;
    }

    private static String refusal(String action, String surface) {
        return "Operator authority required: " + action + " is an operator decision. " + surface;
    }
}
