package io.aria.conductor.execution.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.execution.adk.qoder.QoderProperties;
import io.aria.conductor.execution.credential.RuntimeCredentialException;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.credential.RuntimeCredentialStatus;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.aria.conductor.execution.credential.RuntimeCredentialException.Cause.CIPHER_FAILED;
import static io.aria.conductor.execution.credential.RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link QoderCredentialController} — the operator-facing
 * credential management API for the qoder provider, pinned by the coordinator for B9:
 *
 * <ul>
 *   <li>{@code GET} → 200 masked status (absence is a normal 200 with nulls)</li>
 *   <li>{@code PUT {pat}} → 200 masked status, never an echo of the secret</li>
 *   <li>{@code DELETE} → 204, idempotent</li>
 *   <li>{@code POST /test} → bounded NON-billable structural probe</li>
 *   <li>{@code KEY_NOT_CONFIGURED} store failure → 503 for GET/PUT/POST-test</li>
 * </ul>
 *
 * <p>Security invariants asserted here: the supplied PAT never appears in a response body
 * (not even a substring longer than the allowed mask suffix), a header value, or a log
 * record. All tokens are synthetic ({@code qcp_test_...}); no real credential is used.
 */
class QoderCredentialControllerTest extends WebMvcTestBase {

    private static final String URL = "/api/v1/adk/providers/qoder/credential";
    private static final String PROVIDER_ID = "qoder";
    private static final String SYNTHETIC_PAT = "qcp_test_abcde12345";
    /** The service mask contract: {@code "****"} + last four characters. */
    private static final String MASKED_PAT = "****2345";
    private static final int MASK_SUFFIX_LENGTH = 4;
    private static final String MODEL = "efficient";
    private static final Instant UPDATED_AT = Instant.parse("2026-09-17T10:15:30Z");

    private final RuntimeCredentialService credentialService = mock(RuntimeCredentialService.class);
    private final QoderProperties qoderProperties = new QoderProperties();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        qoderProperties.setModel(MODEL);
        mvc = mockMvcFor(new QoderCredentialController(credentialService, qoderProperties));
    }

    // ---- GET ----

    @Test
    void getCredential_configured_returnsMaskedStatus_onlySuffixVisible() throws Exception {
        when(credentialService.maskedStatus(PROVIDER_ID))
                .thenReturn(new RuntimeCredentialStatus(true, MASKED_PAT, UPDATED_AT));

        MvcResult result = mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.patMasked").value(MASKED_PAT))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-17T10:15:30Z"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andReturn();

        assertNoSecretSubstringBeyondMaskSuffix(responseText(result));
    }

    @Test
    void getCredential_absent_isANormal200WithConfiguredFalseAndNulls() throws Exception {
        when(credentialService.maskedStatus(PROVIDER_ID))
                .thenReturn(new RuntimeCredentialStatus(false, null, null));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.patMasked").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.model").value(MODEL));
    }

    @Test
    void getCredential_keyNotConfigured_returns503WithCode() throws Exception {
        when(credentialService.maskedStatus(PROVIDER_ID))
                .thenThrow(new RuntimeCredentialException(KEY_NOT_CONFIGURED,
                        "Cannot report masked status: PACK_CREDENTIAL_KEY is not configured"));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KEY_NOT_CONFIGURED"));
    }

    @Test
    void getCredential_cipherFailed_returns500WithCode_andNoToken() throws Exception {
        when(credentialService.maskedStatus(PROVIDER_ID))
                .thenThrow(new RuntimeCredentialException(CIPHER_FAILED,
                        "Failed to decrypt the stored runtime credential for provider qoder"));

        MvcResult result = mvc.perform(get(URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CIPHER_FAILED"))
                .andReturn();

        assertNoSecretSubstringBeyondMaskSuffix(responseText(result));
    }

    // ---- PUT ----

    @Test
    void putCredential_storesViaTheService_andReturnsMaskedStatusOnly() throws Exception {
        when(credentialService.maskedStatus(PROVIDER_ID))
                .thenReturn(new RuntimeCredentialStatus(true, MASKED_PAT, UPDATED_AT));

        MvcResult result = mvc.perform(put(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pat", SYNTHETIC_PAT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.patMasked").value(MASKED_PAT))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andReturn();

        verify(credentialService).save(PROVIDER_ID, SYNTHETIC_PAT);
        // The raw body AND every response header must be free of the secret: neither the
        // full token nor any substring longer than the allowed 4-char mask suffix.
        assertNoSecretSubstringBeyondMaskSuffix(responseText(result));
    }

    @ParameterizedTest
    @MethodSource("invalidPatBodies")
    void putCredential_missingOrBlankPat_returns400_withoutEchoingTheValue(Map<String, String> body)
            throws Exception {
        MvcResult result = mvc.perform(put(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("pat is required and must not be blank"))
                .andReturn();

        assertThat(responseText(result)).doesNotContain(SYNTHETIC_PAT);
        verifyNoInteractions(credentialService);
    }

    static Stream<Map<String, String>> invalidPatBodies() {
        return Stream.of(
                Map.of("pat", ""),        // empty
                Map.of("pat", "   "),     // blank
                new HashMap<>());          // key missing entirely
    }

    @Test
    void putCredential_keyNotConfigured_returns503WithCode() throws Exception {
        doThrow(new RuntimeCredentialException(KEY_NOT_CONFIGURED,
                "Cannot store a runtime credential: PACK_CREDENTIAL_KEY is not configured"))
                .when(credentialService).save(PROVIDER_ID, SYNTHETIC_PAT);

        MvcResult result = mvc.perform(put(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pat", SYNTHETIC_PAT))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KEY_NOT_CONFIGURED"))
                .andReturn();

        assertThat(responseText(result)).doesNotContain(SYNTHETIC_PAT);
    }

    // ---- DELETE ----

    @Test
    void deleteCredential_returns204_revokes_andIsIdempotentWhenAbsent() throws Exception {
        mvc.perform(delete(URL)).andExpect(status().isNoContent());
        // Second delete of an absent credential: same 204 no-op, not an error.
        mvc.perform(delete(URL)).andExpect(status().isNoContent());

        verify(credentialService, times(2)).delete(PROVIDER_ID);
    }

    // ---- POST /test (bounded, non-billable structural probe) ----

    @Test
    void test_credentialNotConfigured_reportsFailureReasonWithoutReading() throws Exception {
        when(credentialService.configured(PROVIDER_ID)).thenReturn(false);

        mvc.perform(post(URL + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message")
                        .value("No usable credential is configured for provider qoder; save a PAT and retry."))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.billable").value(false))
                .andExpect(jsonPath("$.costNote").isNotEmpty());

        verify(credentialService, never()).read(PROVIDER_ID);
    }

    @Test
    void test_cipherFailed_reportsFailureReason_andNoToken() throws Exception {
        when(credentialService.configured(PROVIDER_ID)).thenReturn(true);
        when(credentialService.read(PROVIDER_ID))
                .thenThrow(new RuntimeCredentialException(CIPHER_FAILED,
                        "Failed to decrypt the stored runtime credential for provider qoder"));

        MvcResult result = mvc.perform(post(URL + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("CIPHER_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("The stored credential could not be decrypted; re-save the PAT."))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.billable").value(false))
                .andExpect(jsonPath("$.costNote").isNotEmpty())
                .andReturn();

        assertNoSecretSubstringBeyondMaskSuffix(responseText(result));
    }

    @Test
    void test_blankStoredValue_reportsFailureInsteadOfSuccess() throws Exception {
        when(credentialService.configured(PROVIDER_ID)).thenReturn(true);
        when(credentialService.read(PROVIDER_ID)).thenReturn("   ");

        mvc.perform(post(URL + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.billable").value(false));
    }

    @Test
    void test_keyNotConfigured_returns503WithCode() throws Exception {
        when(credentialService.configured(PROVIDER_ID)).thenReturn(true);
        when(credentialService.read(PROVIDER_ID))
                .thenThrow(new RuntimeCredentialException(KEY_NOT_CONFIGURED,
                        "Cannot read runtime credential: PACK_CREDENTIAL_KEY is not configured"));

        mvc.perform(post(URL + "/test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KEY_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "Runtime credential encryption is not configured")));
    }

    @Test
    void test_success_reportsConfiguredModel_disclosesNonBillableProbe_andNeverEchoesPat()
            throws Exception {
        when(credentialService.configured(PROVIDER_ID)).thenReturn(true);
        when(credentialService.read(PROVIDER_ID)).thenReturn(SYNTHETIC_PAT);

        MvcResult result = mvc.perform(post(URL + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.billable").value(false))
                .andExpect(jsonPath("$.costNote")
                        .value(org.hamcrest.Matchers.containsString("no billable inference")))
                .andExpect(jsonPath("$.costNote")
                        .value(org.hamcrest.Matchers.containsString("credits")))
                .andReturn();

        assertNoSecretSubstringBeyondMaskSuffix(responseText(result));
    }

    // ---- No leak into controller logs / request toString ----

    @Test
    void putCredential_tokenEmbeddedInMalformedBody_neverReachesErrorLogsOrBody() throws Exception {
        // A malformed body can carry the PAT verbatim (unquoted JSON value): Jackson's parse
        // error then quotes the token, and the shared GlobalExceptionHandler logs the exception
        // and (on h2) echoes its message. The credential endpoint must not allow that echo.
        String malformedBody = json(Map.of("pat", SYNTHETIC_PAT))
                .replace("\"" + SYNTHETIC_PAT + "\"", SYNTHETIC_PAT);

        Logger controllerLogger = (Logger) LoggerFactory.getLogger(QoderCredentialController.class);
        Logger adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        adviceLogger.addAppender(appender);
        try {
            MvcResult result = mvc.perform(put(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid request body"))
                    .andReturn();

            assertThat(responseText(result)).doesNotContain(SYNTHETIC_PAT);
            // Positive control: the endpoint still logs a safe rejection, so an empty appender
            // cannot make this test pass vacuously.
            assertThat(appender.list).isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(SYNTHETIC_PAT);
                if (event.getThrowableProxy() != null) {
                    assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                            .doesNotContain(SYNTHETIC_PAT);
                }
            }
        } finally {
            controllerLogger.detachAppender(appender);
            adviceLogger.detachAppender(appender);
        }
        verifyNoInteractions(credentialService);
    }

    @Test
    void credentialEndpoints_neverLogOrEchoTheSuppliedToken_andPatRequestToStringIsMasked()
            throws Exception {
        when(credentialService.maskedStatus(PROVIDER_ID))
                .thenReturn(new RuntimeCredentialStatus(true, MASKED_PAT, UPDATED_AT));

        Logger logger = (Logger) LoggerFactory.getLogger(QoderCredentialController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(put(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("pat", SYNTHETIC_PAT))))
                    .andExpect(status().isOk());
            mvc.perform(get(URL)).andExpect(status().isOk());
            mvc.perform(delete(URL)).andExpect(status().isNoContent());

            // Positive control: the controller really did log (otherwise this is vacuous).
            assertThat(appender.list).isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(SYNTHETIC_PAT);
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(SYNTHETIC_PAT);
            }
        } finally {
            logger.detachAppender(appender);
        }

        // The PAT-carrying request type must never render the value, even implicitly.
        QoderCredentialDtos.PatRequest request = new QoderCredentialDtos.PatRequest(SYNTHETIC_PAT);
        assertThat(request.toString()).isEqualTo("PatRequest[pat=****]");
    }

    // ---- helpers ----

    /** Body plus every header value, so a secret cannot hide in either. */
    private static String responseText(MvcResult result) throws Exception {
        StringBuilder text = new StringBuilder(result.getResponse().getContentAsString());
        for (String name : result.getResponse().getHeaderNames()) {
            text.append('\n').append(name).append(": ").append(result.getResponse().getHeader(name));
        }
        return text.toString();
    }

    /**
     * Asserts that the full synthetic PAT and every substring of it longer than the
     * sanctioned mask suffix ({@code ****1234}) are absent from the given text (body plus
     * headers). Callers pair this with an explicit mask assertion so it cannot pass
     * vacuously: an empty response would still be caught by the positive assertions.
     */
    private static void assertNoSecretSubstringBeyondMaskSuffix(String text) {
        assertThat(text).doesNotContain(SYNTHETIC_PAT);
        for (int length = MASK_SUFFIX_LENGTH + 1; length <= SYNTHETIC_PAT.length(); length++) {
            for (int start = 0; start + length <= SYNTHETIC_PAT.length(); start++) {
                assertThat(text)
                        .as("secret substring of length %d leaked", length)
                        .doesNotContain(SYNTHETIC_PAT.substring(start, start + length));
            }
        }
    }
}
