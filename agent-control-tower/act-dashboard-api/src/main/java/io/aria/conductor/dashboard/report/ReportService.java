package io.aria.conductor.dashboard.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.ReportAmendedEvent;
import io.aria.conductor.common.event.ReportGeneratedEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generative-UI report orchestrator.
 *
 * <p>Reports are produced by an LLM, persisted as HTML on disk, and served to
 * the dashboard inside a sandboxed iframe. {@link #generate} creates v1;
 * {@link #amend} produces a new version by re-prompting the LLM with the
 * existing HTML + a user instruction; {@link #regenerate} rebuilds v1 using
 * the original prompt parameters.
 *
 * <p>The LLM is best-effort: if the {@link LlmClient} call fails (no provider,
 * network error, etc.) we fall back to a small placeholder HTML so the rest
 * of the workflow keeps working.
 */
@Slf4j
@Service
public class ReportService {

    private final ReportRepository repository;
    private final LlmClient llmClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ReportProperties reportProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path reportsDir;

    public ReportService(ReportRepository repository,
                         LlmClient llmClient,
                         ApplicationEventPublisher eventPublisher,
                         ReportProperties reportProperties,
                         @Value("${act.reports.dir:./data/reports}") String reportsDir) {
        this.repository = repository;
        this.llmClient = llmClient;
        this.eventPublisher = eventPublisher;
        this.reportProperties = reportProperties;
        this.reportsDir = Paths.get(reportsDir);
    }

    @Transactional
    public ReportArtifact generate(GenerateReportRequest request) {
        MDC.put("operation", "report.generate");
        long start = System.currentTimeMillis();
        try {
            String prompt = buildGeneratePrompt(request.getTitle(), request.getDataScope(), request.getDescription());
            int maxTokens = reportProperties.getGenerateMaxTokens();
            String html = generateHtmlViaLlm(prompt, request.getTitle(), maxTokens);
            String status = validateHtmlCompleteness(html).valid() ? "GENERATED" : "INCOMPLETE";

            ReportArtifact artifact = ReportArtifact.builder()
                    .title(request.getTitle())
                    .sourceRunId(request.getSourceRunId())
                    .owner(request.getOwner())
                    .sensitivity(request.getSensitivity() != null ? request.getSensitivity() : "internal")
                    .dataScope(request.getDataScope())
                    .version(1)
                    .status(status)
                    .amendmentHistory(serializeHistory(List.of(Map.of(
                            "version", 1,
                            "instruction", request.getDescription() == null ? "" : request.getDescription(),
                            "at", Instant.now().toString()
                    ))))
                    .build();

            ReportArtifact saved = repository.save(artifact);
            MDC.put("entityId", saved.getId());
            String path = saveHtml(saved.getId(), 1, html);
            saved.setHtmlPath(path);
            ReportArtifact persisted = repository.save(saved);
            log.info("Report generated, status={}, duration={}ms", status, System.currentTimeMillis() - start);
            eventPublisher.publishEvent(new ReportGeneratedEvent(
                    this, persisted.getId(), persisted.getTitle(),
                    persisted.getOwner() != null ? persisted.getOwner() : ""));
            return persisted;
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    @Transactional
    public ReportArtifact amend(String reportId, AmendReportRequest request) {
        MDC.put("operation", "report.amend");
        MDC.put("entityId", reportId);
        long start = System.currentTimeMillis();
        try {
            ReportArtifact existing = findOrThrow(reportId);
            String currentHtml = readHtml(existing);
            if ("MISSING".equals(existing.getStatus())) {
                throw new IllegalStateException("Cannot amend report " + reportId + ": HTML file missing on disk");
            }
            String prompt = buildAmendPrompt(currentHtml, request.getInstruction());
            int maxTokens = reportProperties.getAmendMaxTokens();
            String newHtml = generateHtmlViaLlm(prompt, existing.getTitle(), maxTokens);
            String status = validateHtmlCompleteness(newHtml).valid() ? "AMENDED" : "INCOMPLETE";

            int nextVersion = (existing.getVersion() == null ? 1 : existing.getVersion()) + 1;
            String path = saveHtml(existing.getId(), nextVersion, newHtml);

            List<Map<String, Object>> history = parseHistory(existing.getAmendmentHistory());
            history.add(Map.of(
                    "version", nextVersion,
                    "instruction", request.getInstruction(),
                    "at", Instant.now().toString()
            ));

            existing.setVersion(nextVersion);
            existing.setStatus(status);
            existing.setHtmlPath(path);
            existing.setAmendedAt(Instant.now());
            existing.setAmendmentHistory(serializeHistory(history));

            ReportArtifact saved = repository.save(existing);
            log.info("Report amended, status={}, version={}, duration={}ms", status, saved.getVersion(), System.currentTimeMillis() - start);
            eventPublisher.publishEvent(new ReportAmendedEvent(
                    this, saved.getId(), request.getInstruction()));
            return saved;
        } finally {
            MDC.remove("operation");
            MDC.remove("entityId");
        }
    }

    @Transactional
    public ReportArtifact regenerate(String reportId) {
        ReportArtifact existing = findOrThrow(reportId);
        String prompt = buildGeneratePrompt(existing.getTitle(), existing.getDataScope(),
                "Regenerate from scratch using the original parameters.");
        int maxTokens = reportProperties.getGenerateMaxTokens();
        String html = generateHtmlViaLlm(prompt, existing.getTitle(), maxTokens);
        String status = validateHtmlCompleteness(html).valid() ? "AMENDED" : "INCOMPLETE";

        int nextVersion = (existing.getVersion() == null ? 1 : existing.getVersion()) + 1;
        String path = saveHtml(existing.getId(), nextVersion, html);

        List<Map<String, Object>> history = parseHistory(existing.getAmendmentHistory());
        history.add(Map.of(
                "version", nextVersion,
                "instruction", "[regenerate]",
                "at", Instant.now().toString()
        ));

        existing.setVersion(nextVersion);
        existing.setStatus(status);
        existing.setHtmlPath(path);
        existing.setAmendedAt(Instant.now());
        existing.setAmendmentHistory(serializeHistory(history));

        return repository.save(existing);
    }

    @Transactional(readOnly = true)
    public ReportArtifact get(String reportId) {
        return findOrThrow(reportId);
    }

    @Transactional(readOnly = true)
    public List<ReportArtifact> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public String getHtml(String reportId) {
        ReportArtifact existing = findOrThrow(reportId);
        return readHtml(existing);
    }

    @Transactional
    public void archive(String reportId) {
        ReportArtifact existing = findOrThrow(reportId);
        existing.setStatus("ARCHIVED");
        repository.save(existing);
    }

    // === LLM helpers ===

    String generateHtmlViaLlm(String prompt, String title, int maxTokens) {
        try {
            LlmRequest request = LlmRequest.of(
                    null,
                    List.of(LlmMessage.user(prompt)),
                    maxTokens);
            LlmResponse response = llmClient.complete(request);
            String content = response == null ? null : response.content();
            if (content == null || content.isBlank()) {
                log.warn("LLM returned empty content for report generation; using placeholder");
                return placeholderHtml(title);
            }
            String html = stripFenceIfPresent(content.trim());
            ValidationResult vr = validateHtmlCompleteness(html);
            if (!vr.valid()) {
                int retryTokens = Math.min(maxTokens * 2, 131072);
                if (retryTokens <= maxTokens) {
                    log.warn("HTML validation failed ({}) and maxTokens already at ceiling; returning partial HTML", vr.reason());
                    return html;
                }
                log.warn("HTML validation failed ({}), retrying with doubled tokens", vr.reason());
                LlmRequest retryRequest = LlmRequest.of(
                        null,
                        List.of(LlmMessage.user(prompt)),
                        retryTokens);
                LlmResponse retryResponse = llmClient.complete(retryRequest);
                String retryContent = retryResponse == null ? null : retryResponse.content();
                if (retryContent == null || retryContent.isBlank()) {
                    log.warn("Retry also returned empty content, returning partial original HTML");
                    return html;
                }
                String retryHtml = stripFenceIfPresent(retryContent.trim());
                ValidationResult retryVr = validateHtmlCompleteness(retryHtml);
                if (!retryVr.valid()) {
                    log.warn("Retry HTML also failed validation ({}), returning partial original HTML", retryVr.reason());
                    return html;
                }
                log.info("Retry with doubled tokens ({}) passed validation", retryTokens);
                return retryHtml;
            }
            return html;
        } catch (Exception e) {
            log.warn("LLM unavailable for report generation, using placeholder: {}", e.getMessage());
            return placeholderHtml(title);
        }
    }

    private String buildGeneratePrompt(String title, String dataScope, String description) {
        return "Generate an HTML report with the following requirements:\n"
             + "- Title: " + nz(title) + "\n"
             + "- Data scope: " + nz(dataScope) + "\n"
             + "- Requirements: " + nz(description) + "\n"
             + "Output ONLY the HTML content, starting with <!DOCTYPE html>.\n"
             + "Use clean, professional styling with inline CSS.";
    }

    private String buildAmendPrompt(String currentHtml, String instruction) {
        return "Here is an existing HTML report:\n"
             + currentHtml + "\n\n"
             + "Please amend it with the following instruction:\n"
             + instruction + "\n\n"
             + "Output ONLY the complete updated HTML.";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Strip a leading ```html / ``` markdown fence if the model added one. */
    private static String stripFenceIfPresent(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.trim();
        }
        return trimmed;
    }

    String placeholderHtml(String title) {
        String safeTitle = title == null ? "Report" : title.replace("<", "&lt;").replace(">", "&gt;");
        return "<!DOCTYPE html>\n"
             + "<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + safeTitle + "</title>"
             + "<style>body{font-family:'Helvetica Neue',Arial,sans-serif;background:#0f1115;color:#e6e7ea;"
             + "padding:48px;line-height:1.6}h1{font-weight:300;letter-spacing:0.02em;border-bottom:1px solid #2a2d36;"
             + "padding-bottom:12px;margin-bottom:24px}.notice{padding:16px 20px;background:#1a1d24;"
             + "border-left:3px solid #e94560;border-radius:2px;font-size:14px;color:#a0aec0}</style></head>"
             + "<body><h1>" + safeTitle + "</h1>"
             + "<div class=\"notice\">Report generation requires LLM connection. "
             + "Configure an active LLM provider and click <strong>Regenerate</strong>.</div></body></html>";
    }

    // === Validation ===

    private record ValidationResult(boolean valid, String reason) {}

    private ValidationResult validateHtmlCompleteness(String html) {
        if (html == null || html.isBlank()) {
            return new ValidationResult(false, "empty");
        }
        if (!html.contains("<body")) {
            return new ValidationResult(false, "missing <body>");
        }
        if (!html.contains("</html>")) {
            return new ValidationResult(false, "missing </html>");
        }
        String text = html.replaceAll("(?s)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isEmpty()) {
            return new ValidationResult(false, "no visible text");
        }
        int wordCount = text.split(" ").length;
        if (wordCount < 10) {
            return new ValidationResult(false, "word count " + wordCount + " < 10");
        }
        return new ValidationResult(true, "ok");
    }

    // === Disk helpers ===

    String saveHtml(String reportId, int version, String htmlContent) {
        try {
            Path versionDir = reportsDir.resolve(reportId).resolve("v" + version);
            Files.createDirectories(versionDir);
            Path indexPath = versionDir.resolve("index.html");
            Files.writeString(indexPath, htmlContent == null ? "" : htmlContent, StandardCharsets.UTF_8);
            return indexPath.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write report HTML to disk", e);
        }
    }

    private String readHtml(ReportArtifact artifact) {
        if (artifact.getHtmlPath() == null) return placeholderHtml(artifact.getTitle());
        Path path = Paths.get(artifact.getHtmlPath());
        if (!Files.exists(path)) {
            artifact.setStatus("MISSING");
            repository.save(artifact);
            log.warn("Report HTML file missing on disk: id={}, path={}", artifact.getId(), path);
            return placeholderHtml(artifact.getTitle());
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read report HTML at {}: {}", path, e.getMessage());
            return placeholderHtml(artifact.getTitle());
        }
    }

    // === History helpers ===

    private List<Map<String, Object>> parseHistory(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (Exception e) {
            log.warn("Failed to parse amendment_history, starting fresh: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeHistory(List<Map<String, Object>> history) {
        try {
            return objectMapper.writeValueAsString(history);
        } catch (Exception e) {
            return "[]";
        }
    }

    private ReportArtifact findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ReportArtifact", id));
    }
}
