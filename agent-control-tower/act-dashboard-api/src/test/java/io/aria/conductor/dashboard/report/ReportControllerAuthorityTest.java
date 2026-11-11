package io.aria.conductor.dashboard.report;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.test.WebMvcTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator boundary of the report mutations (Wave C "authority honesty").
 *
 * <p>Regenerating and archiving a report are operator decisions with no worker-facing
 * twin: the report tools on the Java MCP surface are {@code generate_report},
 * {@code list_reports} and {@code amend_report} ({@code ToolCatalog}; there is no
 * {@code get_report} there), so those two mutations resolve operator authority before the
 * service call. {@code POST /generate} and {@code POST /{id}/amend} stay ungated on
 * purpose, and the catalog policy is the reason: both are {@code WORKER_WRITE}, i.e.
 * worker-callable and not auto-approved.
 * <p>
 * The refusal follows the controller idiom introduced by {@code AgentController.retire}
 * and {@code LlmProviderController}: a worker credential (or a forged cookie mutation) is
 * {@code 403}, an anonymous non-loopback caller is {@code 401}. The three GETs stay open.
 */
class ReportControllerAuthorityTest extends WebMvcTestBase {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String OPERATOR_AUTHORIZATION = "Bearer operator-bearer-secret";
    private static final String WORKER_AUTHORIZATION = "Bearer worker-token";
    private static final String RPT = "rpt-1";

    private final ReportService reportService = mock(ReportService.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final OperatorSessionService sessions = new OperatorSessionService(
            "operator-bearer-secret", Duration.ofHours(8), "http://localhost:5173", false,
            Clock.systemUTC());
    private final MockMvc mvc = mockMvcFor(new ReportController(reportService,
            new OperatorAuthorityResolver(sessions, actorTokens, "")));

    private static ReportArtifact sample(String id, int version, String status) {
        return ReportArtifact.builder()
                .id(id)
                .title("Sample")
                .version(version)
                .status(status)
                .sensitivity("internal")
                .createdAt(NOW)
                .htmlPath("/tmp/" + id + ".html")
                .build();
    }

    /** The sandbox worker's peer address, never loopback. */
    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        };
    }

    private static RequestPostProcessor operatorSessionMutation(OperatorSessionService sessions) {
        OperatorSessionService.OperatorSession session = sessions.createSession();
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            request.setCookies(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()));
            request.addHeader("Origin", "http://localhost:5173");
            request.addHeader(OperatorSessionService.CSRF_HEADER, session.csrfToken());
            return request;
        };
    }

    private void workerTokenIsLive() {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));
    }

    private static String regenerateRefusal() {
        return "Operator authority required: regenerating a report is an operator decision."
                + " Regenerate it on the Reports page.";
    }

    private static String archiveRefusal() {
        return "Operator authority required: archiving a report is an operator decision."
                + " Archive it on the Reports page.";
    }

    // ------------------------------------------------------ POST /{id}/regenerate

    @Test
    void regenerate_workerBearerIsForbidden_andNeverRegenerates() throws Exception {
        workerTokenIsLive();

        mvc.perform(post("/api/v1/reports/" + RPT + "/regenerate")
                        .header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(regenerateRefusal()));

        verify(reportService, never()).regenerate(any());
    }

    @Test
    void regenerate_anonymousRemoteIsUnauthorized_andNeverRegenerates() throws Exception {
        mvc.perform(post("/api/v1/reports/" + RPT + "/regenerate").with(remote()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(regenerateRefusal()));

        verify(reportService, never()).regenerate(any());
    }

    /** Positive control: the dashboard's anonymous loopback caller still regenerates. */
    @Test
    void regenerate_anonymousLoopbackStillRegenerates() throws Exception {
        when(reportService.regenerate(RPT)).thenReturn(sample(RPT, 2, "AMENDED"));

        mvc.perform(post("/api/v1/reports/" + RPT + "/regenerate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.htmlUrl").value("/api/v1/reports/" + RPT + "/html"));

        verify(reportService).regenerate(RPT);
    }

    @Test
    void regenerate_operatorSessionCookieRegenerates() throws Exception {
        when(reportService.regenerate(RPT)).thenReturn(sample(RPT, 3, "AMENDED"));

        mvc.perform(post("/api/v1/reports/" + RPT + "/regenerate")
                        .with(operatorSessionMutation(sessions)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    // ------------------------------------------------------------- DELETE /{id}

    @Test
    void archive_workerBearerIsForbidden_andNeverArchives() throws Exception {
        workerTokenIsLive();

        mvc.perform(delete("/api/v1/reports/" + RPT).header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(archiveRefusal()));

        verify(reportService, never()).archive(any());
    }

    @Test
    void archive_anonymousRemoteIsUnauthorized_andNeverArchives() throws Exception {
        mvc.perform(delete("/api/v1/reports/" + RPT).with(remote()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(archiveRefusal()));

        verify(reportService, never()).archive(any());
    }

    @Test
    void archive_anonymousLoopbackStillArchives() throws Exception {
        mvc.perform(delete("/api/v1/reports/" + RPT))
                .andExpect(status().isNoContent());

        verify(reportService).archive(RPT);
    }

    @Test
    void archive_operatorSessionCookieArchives() throws Exception {
        mvc.perform(delete("/api/v1/reports/" + RPT).with(operatorSessionMutation(sessions)))
                .andExpect(status().isNoContent());

        verify(reportService).archive(RPT);
    }

    /**
     * The three GETs are the report page's reads and stay open: the dashboard does not
     * authenticate them, and the disclosed loopback GET residual is not this route's.
     */
    @Test
    void theThreeReadsStayOpenToARemoteCaller() throws Exception {
        when(reportService.list()).thenReturn(List.of(sample(RPT, 1, "GENERATED")));
        when(reportService.get(RPT)).thenReturn(sample(RPT, 1, "GENERATED"));
        when(reportService.getHtml(RPT)).thenReturn("<html>report</html>");

        mvc.perform(get("/api/v1/reports").with(remote()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(RPT));
        mvc.perform(get("/api/v1/reports/" + RPT).with(remote()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RPT));
        mvc.perform(get("/api/v1/reports/" + RPT + "/html").with(remote()))
                .andExpect(status().isOk());

        verify(reportService).list();
        verify(reportService).get(RPT);
        verify(reportService).getHtml(RPT);
    }
}
