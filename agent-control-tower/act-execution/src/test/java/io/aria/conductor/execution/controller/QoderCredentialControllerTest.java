package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.RuntimeCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.repository.RuntimeCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.security.ActorAuthenticationFilter;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.test.WebMvcTestBase;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operator boundary and masking contract of the Qoder credential surface
 * (plan section 2.2): masked reads, encrypted replacement, revocation, no
 * model call on readiness, and a bounded test that is the only model-spending
 * path.
 */
class QoderCredentialControllerTest extends WebMvcTestBase {

    private static final String SECRET = "synthetic-secret-42";
    private static final String REF = RuntimeCredentialService.QODER_CREDENTIAL_REFERENCE;
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RuntimeCredentialRepository repository = mock(RuntimeCredentialRepository.class);
    private final RuntimeCredentialService credentials =
            new RuntimeCredentialService(repository, "test-runtime-credential-key", CLOCK);
    private final OperatorSessionService operatorSessions = new OperatorSessionService(
            "operator-bearer-secret", Duration.ofHours(8), "http://localhost:5173", false, CLOCK);
    private final QoderCredentialController.CredentialProbe probe =
            mock(QoderCredentialController.CredentialProbe.class);
    private final MockMvc mvc = mockMvcFor(
            new QoderCredentialController(credentials, operatorSessions, probe,
                    QoderCredentialController.TEST_TIMEOUT));

    /**
     * The configured operator credential carries no expiry (see
     * {@code ActorPrincipal.operator}); a fixed expiry here would silently start
     * failing against the controller's real clock once that instant passed.
     */
    private static ActorPrincipal operator() {
        return ActorPrincipal.operator(null);
    }

    /** A non-operator principal; the rejection is a role decision, never an expiry race. */
    private static ActorPrincipal worker() {
        return ActorPrincipal.worker(UUID.randomUUID(), null);
    }

    /** Stores one credential through the service and keeps it resolvable afterwards. */
    private RuntimeCredential storedRow() {
        when(repository.findById(REF)).thenReturn(Optional.empty());
        credentials.putQoder(SECRET, operator());
        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(repository).save(captor.capture());
        RuntimeCredential row = captor.getValue();
        when(repository.findById(REF)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void getWithoutIdentityIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/adk/providers/qoder/credential"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getWithWorkerIdentityIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, worker()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReturnsExactMaskedMetadataAndNeverCallsAModel() throws Exception {
        storedRow();

        mvc.perform(get("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialRef").value(REF))
                .andExpect(jsonPath("$.coreId").value(RuntimeCredentialService.QODER_CORE_ID))
                .andExpect(jsonPath("$.environmentVariable")
                        .value(RuntimeCredentialService.QODER_ENVIRONMENT_VARIABLE))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedSecret").value("********"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
        verifyNoInteractions(probe);
    }

    @Test
    void credentialViewReportsWhetherTheBoundedTestIsWired() throws Exception {
        storedRow();
        MockMvc noProbeMvc = mockMvcFor(new QoderCredentialController(
                credentials, operatorSessions, null, QoderCredentialController.TEST_TIMEOUT));

        mvc.perform(get("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testSupported").value(true));

        noProbeMvc.perform(get("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testSupported").value(false));

        noProbeMvc.perform(put("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testSupported").value(false));
        verifyNoInteractions(probe);
    }

    @Test
    void putStoresEncryptedAndNeverEchoesTheSecret() throws Exception {
        when(repository.findById(REF)).thenReturn(Optional.empty());

        mvc.perform(put("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedSecret").value("********"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));

        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEncValue()).isNotEqualTo(SECRET);
        verifyNoInteractions(probe);
    }

    @Test
    void putWithWorkerIdentityIsForbidden() throws Exception {
        mvc.perform(put("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, worker())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
        verify(repository, never()).save(any(RuntimeCredential.class));
    }

    @Test
    void cookieMutationWithoutCsrfTokenIsForbidden() throws Exception {
        OperatorSessionService.OperatorSession session = operatorSessions.createSession();

        mvc.perform(put("/api/v1/adk/providers/qoder/credential")
                        .cookie(new jakarta.servlet.http.Cookie(
                                OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any(RuntimeCredential.class));
    }

    @Test
    void cookieMutationWithCsrfTokenStoresTheCredential() throws Exception {
        OperatorSessionService.OperatorSession session = operatorSessions.createSession();
        when(repository.findById(REF)).thenReturn(Optional.empty());

        mvc.perform(put("/api/v1/adk/providers/qoder/credential")
                        .cookie(new jakarta.servlet.http.Cookie(
                                OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header("Origin", "http://localhost:5173")
                        .header(OperatorSessionService.CSRF_HEADER, session.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void deleteRevokesFutureResolution() throws Exception {
        storedRow();

        mvc.perform(delete("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isNoContent());

        verify(repository).deleteById(REF);
        when(repository.findById(REF)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> credentials.resolve(REF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured");
        verifyNoInteractions(probe);
    }

    @Test
    void testWithoutProbeMakesNoCallAndReportsNoResult() throws Exception {
        storedRow();
        MockMvc noProbeMvc = mockMvcFor(new QoderCredentialController(
                credentials, operatorSessions, null, QoderCredentialController.TEST_TIMEOUT));

        noProbeMvc.perform(post("/api/v1/adk/providers/qoder/credential/test")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.tested").value(false));
        verifyNoInteractions(probe);
    }

    @Test
    void testWhenNotConfiguredIsConflict() throws Exception {
        when(repository.findById(REF)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/adk/providers/qoder/credential/test")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isConflict());
        verifyNoInteractions(probe);
    }

    @Test
    void testUsesTheBoundedProbeAndReportsUnknownUsageAsUnknown() throws Exception {
        storedRow();
        when(probe.test(any(SecretBundle.class), any(Duration.class))).thenReturn(
                new QoderCredentialController.CredentialTestOutcome(
                        true, "efficient", "session accepted",
                        new UsageSnapshot(null, null, null, "efficient")));

        mvc.perform(post("/api/v1/adk/providers/qoder/credential/test")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tested").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.usage.inputTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.usage.credits").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.costDisclosure").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));

        ArgumentCaptor<SecretBundle> captor = ArgumentCaptor.forClass(SecretBundle.class);
        verify(probe).test(captor.capture(), org.mockito.ArgumentMatchers.eq(
                QoderCredentialController.TEST_TIMEOUT));
        assertThat(captor.getValue().environment())
                .containsEntry(RuntimeCredentialService.QODER_ENVIRONMENT_VARIABLE, SECRET);
    }

    // ------------------------------------------------------ payload and key readiness

    @Test
    void credentialRequestCarrierNeverPrintsItsSecret() {
        assertThat(new QoderCredentialController.QoderCredentialRequest(SECRET).toString())
                .doesNotContain(SECRET)
                .isEqualTo("QoderCredentialRequest[redacted]");
    }

    @Test
    void malformedPutBodyIsRejectedWithAFixedMessageAndNeverLogged() throws Exception {
        ListAppender<ILoggingEvent> logs = attachedLogCapture();
        MvcResult result;
        try {
            // A plausible paste error: the secret without its JSON quotes. Jackson's
            // parse exception names the unrecognized token, so the default error path
            // would log the plaintext prefix of the credential.
            result = mvc.perform(put("/api/v1/adk/providers/qoder/credential")
                            .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"secret\":" + SECRET + "}"))
                    .andReturn();
        } finally {
            detachLogCapture(logs);
        }
        assertThat(logs.list)
                .as("no log event may carry a fragment of the plaintext payload")
                .noneMatch(event -> renderedLogText(event).contains(SECRET.substring(0, 8)));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("Malformed Qoder credential payload")
                .doesNotContain(SECRET);
        verify(repository, never()).save(any(RuntimeCredential.class));
    }

    @Test
    void getWithTheConfiguredKeyReportsTheCredentialAsReadable() throws Exception {
        storedRow();

        mvc.perform(get("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.encryptionKeyConfigured").value(true));
    }

    @Test
    void getWithAMissingEncryptionKeyReportsTheStoredCredentialAsUnreadable() throws Exception {
        storedRow();
        MockMvc unkeyedMvc = mockMvcFor(new QoderCredentialController(
                new RuntimeCredentialService(repository, "  ", CLOCK),
                operatorSessions, probe, QoderCredentialController.TEST_TIMEOUT));

        unkeyedMvc.perform(get("/api/v1/adk/providers/qoder/credential")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.encryptionKeyConfigured").value(false))
                .andExpect(jsonPath("$.maskedSecret").value("********"));
    }

    @Test
    void testWithoutKeyReportsTheMissingKeyNotTheMissingBridge() throws Exception {
        storedRow();
        MockMvc unkeyedMvc = mockMvcFor(new QoderCredentialController(
                new RuntimeCredentialService(repository, "  ", CLOCK),
                operatorSessions, null, QoderCredentialController.TEST_TIMEOUT));

        unkeyedMvc.perform(post("/api/v1/adk/providers/qoder/credential/test")
                        .requestAttr(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, operator()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.tested").value(false))
                .andExpect(jsonPath("$.reason", org.hamcrest.Matchers.containsString("encryption key")));
        verify(probe, never()).test(any(SecretBundle.class), any(Duration.class));
    }

    // ------------------------------------------------------------------ helpers

    private static ListAppender<ILoggingEvent> attachedLogCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
        return appender;
    }

    private static void detachLogCapture(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    }

    /** Message plus every throwable message of one event, the shape a log consumer would see. */
    private static String renderedLogText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
            text.append('\n').append(thrown.getClassName()).append(": ").append(thrown.getMessage());
        }
        return text.toString();
    }
}
