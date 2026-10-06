package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.CoreCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.credential.CredentialProbe;
import io.aria.conductor.execution.repository.CoreCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
 * Operator boundary and masking contract of the plain core credential surface
 * (2026-10-05 simplification): loopback operator resolution through the shared
 * {@link OperatorAuthorityResolver}, masked reads that never echo the stored
 * value, revocation, and a bounded test that is the only model-spending path.
 */
class CoreCredentialControllerTest extends WebMvcTestBase {

    private static final String SECRET = "pat-secret-value-1234";
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final CoreCredentialRepository repository = mock(CoreCredentialRepository.class);
    /** The store behind the mocked repository, so put -> metadata round trips are real. */
    private final Map<String, CoreCredential> store = new HashMap<>();
    private final CoreCredentialService credentials = new CoreCredentialService(repository, CLOCK);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final CredentialProbe probe = mock(CredentialProbe.class);
    private final OperatorSessionService operatorSessions = new OperatorSessionService(
            "operator-bearer-secret", Duration.ofHours(8), "http://localhost:5173", false, CLOCK);
    private MockMvc mvc;

    @BeforeEach
    void rig() {
        when(repository.save(any(CoreCredential.class))).thenAnswer(invocation -> {
            CoreCredential row = invocation.getArgument(0);
            store.put(row.getCoreId(), row);
            return row;
        });
        when(repository.findById(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        doAnswer(invocation -> store.remove((String) invocation.getArgument(0)))
                .when(repository).deleteById(anyString());
        mvc = mockMvcFor(new CoreCredentialController(credentials,
                new OperatorAuthorityResolver(operatorSessions, actorTokens, ""), probe,
                CoreCredentialController.TEST_TIMEOUT));
    }

    /** The loopback client of a local single-operator deployment. */
    private static RequestPostProcessor loopback() {
        return request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        };
    }

    // -------------------------------------------------------------- put and get

    @Test
    void loopbackPutStoresAndReturnsMaskedMetadata() throws Exception {
        mvc.perform(put("/api/v1/cores/qoder/credential").with(loopback())
                        .contentType("application/json")
                        .content(SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialRef").value("qoder:operator"))
                .andExpect(jsonPath("$.coreId").value("qoder"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedSecret").value("****1234"))
                .andExpect(jsonPath("$.environmentVariable").value("QODER_PERSONAL_ACCESS_TOKEN"))
                .andExpect(jsonPath("$.testSupported").value(true))
                .andExpect(content().string(not(containsString(SECRET))));
        verifyNoInteractions(probe);
    }

    @Test
    void getReturnsMaskedMetadataAndNeverTheValue() throws Exception {
        credentials.put("qoder", SECRET, ActorPrincipal.operator(null));

        mvc.perform(get("/api/v1/cores/qoder/credential").with(loopback()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedSecret").value("****1234"))
                .andExpect(content().string(not(containsString(SECRET))));
        verifyNoInteractions(probe);
    }

    @Test
    void blankPutBodyIsRejectedWithAFixedMessage() throws Exception {
        mvc.perform(put("/api/v1/cores/qoder/credential").with(loopback())
                        .contentType("application/json")
                        .content("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A runtime credential value is required"));
        verify(repository, never()).save(any(CoreCredential.class));
    }

    // ------------------------------------------------------- authority boundary

    @Test
    void anonymousRemotePutIsUnauthorized() throws Exception {
        mvc.perform(put("/api/v1/cores/qoder/credential")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .contentType("application/json")
                        .content(SECRET))
                .andExpect(status().isUnauthorized());
        verify(repository, never()).save(any(CoreCredential.class));
    }

    /**
     * A resolvable worker bearer is authenticated but never promoted to
     * operator -- not even from loopback; the refusal is the fixed 403.
     */
    @Test
    void workerBearerPutRemainsForbidden() throws Exception {
        when(actorTokens.resolveBearer("Bearer worker-token")).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));

        mvc.perform(put("/api/v1/cores/qoder/credential").with(loopback())
                        .header("Authorization", "Bearer worker-token")
                        .contentType("application/json")
                        .content(SECRET))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Operator authority required"));
        verify(repository, never()).save(any(CoreCredential.class));
    }

    @Test
    void cookieMutationWithoutCsrfTokenIsForbidden() throws Exception {
        OperatorSessionService.OperatorSession session = operatorSessions.createSession();

        mvc.perform(put("/api/v1/cores/qoder/credential").with(loopback())
                        .cookie(new jakarta.servlet.http.Cookie(
                                OperatorSessionService.COOKIE_NAME, session.sessionId()))
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(SECRET))
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any(CoreCredential.class));
    }

    // ----------------------------------------------------------------- delete

    @Test
    void deleteRevokesFutureResolution() throws Exception {
        credentials.put("qoder", SECRET, ActorPrincipal.operator(null));

        mvc.perform(delete("/api/v1/cores/qoder/credential").with(loopback()))
                .andExpect(status().isNoContent());

        verify(repository).deleteById("qoder");
        assertThatThrownBy(() -> credentials.resolve("qoder:operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime credential is not configured: qoder:operator");
        verifyNoInteractions(probe);
    }

    /** The metadata/delete validation (2026-10-05 ruling): a non-qoder core id is a client error. */
    @Test
    void getWithUnsupportedCoreIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/cores/other/credential").with(loopback()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported core credential: other"));
    }

    @Test
    void deleteWithUnsupportedCoreIdIsBadRequest() throws Exception {
        mvc.perform(delete("/api/v1/cores/other/credential").with(loopback()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported core credential: other"));
        verify(repository, never()).deleteById("other");
    }

    // ------------------------------------------------------- bounded test call

    @Test
    void testWithoutCredentialIsConflict() throws Exception {
        mvc.perform(post("/api/v1/cores/qoder/credential/test").with(loopback()))
                .andExpect(status().isConflict());
        verifyNoInteractions(probe);
    }

    @Test
    void testWithoutProbeMakesNoCallAndReportsNoResult() throws Exception {
        credentials.put("qoder", SECRET, ActorPrincipal.operator(null));
        MockMvc noProbeMvc = mockMvcFor(new CoreCredentialController(credentials,
                new OperatorAuthorityResolver(operatorSessions, actorTokens, ""), null,
                CoreCredentialController.TEST_TIMEOUT));

        noProbeMvc.perform(post("/api/v1/cores/qoder/credential/test").with(loopback()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.tested").value(false));
        verifyNoInteractions(probe);
    }

    @Test
    void testUsesTheBoundedProbeAndReportsUnknownUsageAsUnknown() throws Exception {
        credentials.put("qoder", SECRET, ActorPrincipal.operator(null));
        when(probe.test(any(SecretBundle.class), any(Duration.class))).thenReturn(
                new CredentialProbe.CredentialTestOutcome(true, "efficient", "session accepted",
                        new UsageSnapshot(null, null, null, "efficient")));

        mvc.perform(post("/api/v1/cores/qoder/credential/test").with(loopback()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tested").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.usage.inputTokens").value(nullValue()))
                .andExpect(jsonPath("$.usage.credits").value(nullValue()))
                .andExpect(jsonPath("$.costDisclosure").isNotEmpty())
                .andExpect(content().string(not(containsString(SECRET))));

        ArgumentCaptor<SecretBundle> captor = ArgumentCaptor.forClass(SecretBundle.class);
        verify(probe).test(captor.capture(), eq(CoreCredentialController.TEST_TIMEOUT));
        assertThat(captor.getValue().environment())
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", SECRET);
    }
}
