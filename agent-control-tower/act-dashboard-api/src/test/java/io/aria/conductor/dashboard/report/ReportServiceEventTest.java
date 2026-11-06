package io.aria.conductor.dashboard.report;

import io.aria.conductor.common.event.ReportAmendedEvent;
import io.aria.conductor.common.event.ReportGeneratedEvent;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ReportService} publishes the expected
 * {@link org.springframework.context.ApplicationEvent}s on
 * {@code generate} and {@code amend}.
 *
 * <p>Mirrors the constructor-injected mock pattern in {@code ReportServiceTest}
 * and adds a mocked {@link ApplicationEventPublisher} captured via
 * {@link ArgumentCaptor}.
 */
class ReportServiceEventTest {

    @TempDir
    Path tempDir;

    private ReportRepository repository;
    private LlmClient llmClient;
    private ApplicationEventPublisher eventPublisher;
    private ReportProperties reportProperties;
    private ReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportRepository.class);
        llmClient = mock(LlmClient.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        reportProperties = mock(ReportProperties.class);
        when(reportProperties.getGenerateMaxTokens()).thenReturn(16384);
        when(reportProperties.getAmendMaxTokens()).thenReturn(16384);
        service = new ReportService(repository, llmClient, eventPublisher, reportProperties, tempDir.toString());

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
    void generate_publishesReportGeneratedEvent() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("<!DOCTYPE html><html><body>hi</body></html>", 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("Q1 Summary")
                .description("Summarize agent runs in Q1")
                .dataScope("runs:Q1")
                .owner("alice")
                .build();

        ReportArtifact result = service.generate(request);

        ArgumentCaptor<ReportGeneratedEvent> captor =
                ArgumentCaptor.forClass(ReportGeneratedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ReportGeneratedEvent event = captor.getValue();
        assertThat(event.getReportId()).isEqualTo(result.getId());
        assertThat(event.getTitle()).isEqualTo("Q1 Summary");
        assertThat(event.getOwner()).isEqualTo("alice");
    }

    @Test
    void generate_withoutOwner_publishesEventWithEmptyOwner() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("<!DOCTYPE html><body>x</body></html>", 1, 1, "stop", null));

        GenerateReportRequest request = GenerateReportRequest.builder()
                .title("No-Owner")
                .description("desc")
                .build();

        service.generate(request);

        ArgumentCaptor<ReportGeneratedEvent> captor =
                ArgumentCaptor.forClass(ReportGeneratedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getOwner()).isEqualTo("");
    }

    @Test
    void amend_publishesReportAmendedEvent() throws Exception {
        ReportArtifact existing = ReportArtifact.builder()
                .id("rpt-1")
                .title("Weekly")
                .version(1)
                .status("GENERATED")
                .htmlPath(seedHtml("rpt-1", 1, "<!DOCTYPE html><html><body>v1</body></html>"))
                .createdAt(java.time.Instant.now())
                .amendmentHistory("[]")
                .build();
        when(repository.findById("rpt-1")).thenReturn(Optional.of(existing));
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("<!DOCTYPE html><body>v2</body></html>", 1, 1, "stop", null));

        AmendReportRequest request = AmendReportRequest.builder()
                .instruction("Add a section for critical blockers")
                .build();

        service.amend("rpt-1", request);

        ArgumentCaptor<ReportAmendedEvent> captor =
                ArgumentCaptor.forClass(ReportAmendedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ReportAmendedEvent event = captor.getValue();
        assertThat(event.getReportId()).isEqualTo("rpt-1");
        assertThat(event.getInstruction()).isEqualTo("Add a section for critical blockers");
    }

    private String seedHtml(String reportId, int version, String html) throws Exception {
        Path dir = tempDir.resolve(reportId).resolve("v" + version);
        Files.createDirectories(dir);
        Path file = dir.resolve("index.html");
        Files.writeString(file, html);
        return file.toString();
    }
}
