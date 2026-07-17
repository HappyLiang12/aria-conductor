package io.aria.conductor.dashboard.report;

import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    @TempDir
    Path tempDir;

    private ReportRepository repository;
    private LlmClient llmClient;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private ReportProperties reportProperties;
    private ReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportRepository.class);
        llmClient = mock(LlmClient.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        reportProperties = mock(ReportProperties.class);
        when(reportProperties.getGenerateMaxTokens()).thenReturn(16384);
        when(reportProperties.getAmendMaxTokens()).thenReturn(16384);
        service = new ReportService(repository, llmClient, eventPublisher, reportProperties, tempDir.toString());

        // Save returns the same artifact (with id assigned via @PrePersist if needed).
        when(repository.save(any(ReportArtifact.class))).thenAnswer(inv -> {
            ReportArtifact arg = inv.getArgument(0);
            if (arg.getId() == null) {
                arg.setId("report-" + System.nanoTime());
            }
            if (arg.getVersion() == null) arg.setVersion(1);
            if (arg.getStatus() == null) arg.setStatus("GENERATED");
            if (arg.getCreatedAt() == null) arg.setCreatedAt(java.time.Instant.now());
            return arg;
        });
    }

    @Test
    void generate_writesHtmlToDiskAndPersistsRecord() throws Exception {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("<!DOCTYPE html><html><body><h1>Q1 Summary</h1><p>This report summarizes all agent activities across the first quarter with detailed analytics.</p></body></html>", 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Q1 Summary")
                .description("Summarize agent runs in Q1")
                .dataScope("runs:Q1")
                .owner("alice")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getId()).isNotBlank();
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo("GENERATED");
        assertThat(result.getHtmlPath()).isNotNull();

        Path onDisk = Path.of(result.getHtmlPath());
        assertThat(Files.exists(onDisk)).isTrue();
        assertThat(Files.readString(onDisk)).contains("<!DOCTYPE html>");
        // save called once before disk-write (to assign id) and once after (to persist html_path).
        verify(repository, times(2)).save(any(ReportArtifact.class));
    }

    @Test
    void generate_whenLlmFails_usesPlaceholder() throws Exception {
        when(llmClient.complete(any(LlmRequest.class))).thenThrow(new RuntimeException("offline"));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Outage Report")
                .description("Fallback path")
                .build();

        ReportArtifact result = service.generate(request);

        Path onDisk = Path.of(result.getHtmlPath());
        String html = Files.readString(onDisk);
        assertThat(html).contains("Report generation requires LLM connection");
        assertThat(html).contains("Outage Report");
    }

    @Test
    void amend_createsNewVersionAndUpdatesHistory() throws Exception {
        // Seed an existing v1 artifact
        ReportArtifact existing = ReportArtifact.builder()
                .id("rpt-1")
                .title("Weekly")
                .version(1)
                .status("GENERATED")
                .htmlPath(seedHtml("rpt-1", 1, "<!DOCTYPE html><html><body><h1>Weekly Report</h1><p>The original version one content with all the initial findings.</p></body></html>"))
                .createdAt(java.time.Instant.now())
                .amendmentHistory("[]")
                .build();
        when(repository.findById("rpt-1")).thenReturn(Optional.of(existing));
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("<!DOCTYPE html><html><body><h1>Weekly Report</h1><p>This amended version now includes details about critical blockers found during testing.</p></body></html>", 1, 1, "stop", null));

        AmendReportRequest request = AmendReportRequest.builder()
                .instruction("Add a section for critical blockers")
                .build();

        ReportArtifact result = service.amend("rpt-1", request);

        assertThat(result.getVersion()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo("AMENDED");
        assertThat(result.getAmendedAt()).isNotNull();
        assertThat(result.getAmendmentHistory()).contains("critical blockers");

        Path v2 = Path.of(result.getHtmlPath());
        assertThat(Files.readString(v2)).contains("critical blockers found during testing");
    }

    @Test
    void getHtml_returnsFileContent() throws Exception {
        String path = seedHtml("rpt-2", 1, "<!DOCTYPE html><body>persisted</body>");
        ReportArtifact existing = ReportArtifact.builder()
                .id("rpt-2")
                .title("X")
                .version(1)
                .status("GENERATED")
                .htmlPath(path)
                .createdAt(java.time.Instant.now())
                .build();
        when(repository.findById("rpt-2")).thenReturn(Optional.of(existing));

        String html = service.getHtml("rpt-2");

        assertThat(html).contains("persisted");
    }

    @Test
    void list_delegatesToFindAllByOrderByCreatedAtDesc() {
        ReportArtifact a = ReportArtifact.builder().id("a").title("A").version(1).status("GENERATED").createdAt(java.time.Instant.now()).build();
        ReportArtifact b = ReportArtifact.builder().id("b").title("B").version(1).status("GENERATED").createdAt(java.time.Instant.now()).build();
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a, b));

        List<ReportArtifact> result = service.list();

        assertThat(result).containsExactly(a, b);
        verify(repository, atLeastOnce()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void get_missingId_throwsNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regenerate_bumpsVersionAndKeepsHistory() throws Exception {
        ReportArtifact existing = ReportArtifact.builder()
                .id("rpt-3")
                .title("Daily")
                .dataScope("runs:today")
                .version(1)
                .status("GENERATED")
                .htmlPath(seedHtml("rpt-3", 1, "<!DOCTYPE html><html><body><h1>Daily Report</h1><p>The original daily report content before regeneration was requested.</p></body></html>"))
                .createdAt(java.time.Instant.now())
                .amendmentHistory("[]")
                .build();
        when(repository.findById("rpt-3")).thenReturn(Optional.of(existing));
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("<!DOCTYPE html><html><body><h1>Daily Report</h1><p>This is a freshly regenerated report with completely updated content for today.</p></body></html>", 1, 1, "stop", null));

        ReportArtifact result = service.regenerate("rpt-3");

        assertThat(result.getVersion()).isEqualTo(2);
        assertThat(result.getAmendmentHistory()).contains("[regenerate]");
        assertThat(Files.readString(Path.of(result.getHtmlPath()))).contains("freshly regenerated");
    }

    // === Issue #32: Report Truncation Fix ===

    @Test
    void generate_truncatedHtmlMissingBody_setsIncomplete() {
        String truncatedHtml = "<!DOCTYPE html><html><head><style>body{color:red}</style></head></html>";
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(truncatedHtml, 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Truncated Report")
                .description("Test truncation handling for missing body tag")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
        verify(llmClient, times(2)).complete(any(LlmRequest.class));
    }

    @Test
    void generate_truncatedHtmlMissingClosingHtml_setsIncomplete() {
        String truncatedHtml = "<!DOCTYPE html><html><body>content here";
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(truncatedHtml, 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Truncated Report")
                .description("Test truncation handling for missing closing html tag")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
        verify(llmClient, times(2)).complete(any(LlmRequest.class));
    }

    @Test
    void generate_emptyBodyContent_setsIncomplete() {
        String emptyHtml = "<!DOCTYPE html><html><body><h1>Hi</h1></body></html>";
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(emptyHtml, 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Empty Report")
                .description("Test validation of content with fewer than 10 words")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
        verify(llmClient, times(2)).complete(any(LlmRequest.class));
    }

    @Test
    void generate_retryWithDoubledTokens_succeeds() {
        String truncated = "<!DOCTYPE html><html><head><style>body{}</style>";
        String validHtml = "<!DOCTYPE html><html><body><h1>Complete Report</h1>"
                + "<p>This is a full report with sufficient content to pass the word count validation check.</p>"
                + "</body></html>";

        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(truncated, 1, 1, "stop", null))
                .thenReturn(new LlmResponse(validHtml, 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Retry Report")
                .description("Test retry with doubled maxTokens")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getStatus()).isEqualTo("GENERATED");

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient, times(2)).complete(captor.capture());
        List<LlmRequest> requests = captor.getAllValues();
        assertThat(requests.get(0).maxTokens()).isEqualTo(16384);
        assertThat(requests.get(1).maxTokens()).isEqualTo(32768);
    }

    @Test
    void generate_bothAttemptsFail_setsIncomplete() {
        String truncated = "<!DOCTYPE html><html><head><style>body{}</style>";
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(truncated, 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Failing Report")
                .description("Test when both attempts produce truncated HTML")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
        verify(llmClient, times(2)).complete(any(LlmRequest.class));
    }

    @Test
    void generate_placeholderHtmlIsValidOnLlmException() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenThrow(new RuntimeException("offline"));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Outage Report")
                .description("Fallback path")
                .build();

        ReportArtifact result = service.generate(request);

        assertThat(result.getStatus()).isEqualTo("GENERATED");
        Path onDisk = Path.of(result.getHtmlPath());
        assertThat(onDisk).exists();
    }

    @Test
    void amend_truncatedRetryFails_setsIncomplete() throws Exception {
        ReportArtifact existing = ReportArtifact.builder()
                .id("rpt-amend-1")
                .title("Weekly")
                .version(1)
                .status("GENERATED")
                .htmlPath(seedHtml("rpt-amend-1", 1,
                        "<!DOCTYPE html><html><body><h1>Weekly Report</h1>"
                                + "<p>This is the original report content with enough words to pass validation.</p>"
                                + "</body></html>"))
                .createdAt(java.time.Instant.now())
                .amendmentHistory("[]")
                .build();
        when(repository.findById("rpt-amend-1")).thenReturn(Optional.of(existing));

        String truncated = "<!DOCTYPE html><html><body>incomplete";
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(truncated, 1, 1, "stop", null));

        AmendReportRequest request = AmendReportRequest.builder()
                .instruction("Add a section for critical blockers")
                .build();

        ReportArtifact result = service.amend("rpt-amend-1", request);

        assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
        verify(llmClient, times(2)).complete(any(LlmRequest.class));
    }

    @Test
    void readHtml_missingFile_marksMissing() {
        String nonExistentPath = tempDir.resolve("nonexistent/file.html").toString();
        ReportArtifact artifact = ReportArtifact.builder()
                .id("rpt-missing")
                .title("Missing File")
                .version(1)
                .status("GENERATED")
                .htmlPath(nonExistentPath)
                .createdAt(java.time.Instant.now())
                .build();
        when(repository.findById("rpt-missing")).thenReturn(Optional.of(artifact));

        String html = service.getHtml("rpt-missing");

        assertThat(html).contains("Report generation requires LLM connection");

        ArgumentCaptor<ReportArtifact> captor = ArgumentCaptor.forClass(ReportArtifact.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        boolean hasMissingStatus = captor.getAllValues().stream()
                .anyMatch(a -> "MISSING".equals(a.getStatus()));
        assertThat(hasMissingStatus).isTrue();
    }

    private String seedHtml(String reportId, int version, String html) throws Exception {
        Path dir = tempDir.resolve(reportId).resolve("v" + version);
        Files.createDirectories(dir);
        Path file = dir.resolve("index.html");
        Files.writeString(file, html);
        return file.toString();
    }
}
