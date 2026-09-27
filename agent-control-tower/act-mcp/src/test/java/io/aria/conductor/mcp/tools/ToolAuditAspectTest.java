package io.aria.conductor.mcp.tools;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour + security tests for {@link ToolAuditAspect}.
 *
 * <p>The aspect logs every {@code @Tool} invocation; credential-bearing arguments
 * ({@code apiKey}, {@code token}, {@code pat}, ...) must be redacted, non-sensitive
 * sibling arguments must stay visible (the positive control that makes the redaction
 * assertions non-vacuous), and unavailable parameter names must fail closed instead of
 * logging raw values. All tokens here are synthetic ({@code qcp_test_...}); no real
 * credential is ever used.
 */
class ToolAuditAspectTest {

    private static final String SYNTHETIC_TOKEN = "qcp_test_abcde12345";
    private static final String NON_SENSITIVE = "eu-west-1";

    static class AuditedFixture {
        @Tool(name = "fixture_tool")
        public String call() {
            return ToolResponses.ok("done");
        }

        @Tool(name = "fixture_tool_credential")
        public String withCredential(String apiKey, String region) {
            return ToolResponses.ok(region);
        }

        @Tool(name = "fixture_tool_all_sensitive")
        public String withAllSensitiveNames(String apiKey, String api_key, String token, String secret,
                                            String pat, String password, String credential,
                                            String authorization, String region) {
            return ToolResponses.ok(region);
        }

        @Tool(name = "fixture_tool_failing")
        public String fail() {
            throw new IllegalStateException("boom");
        }

        @Tool(name = "fixture_tool_failing_with_token")
        public String failWithToken(String token) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void audit_passesThrough_andIsInvoked() throws Exception {
        ToolAuditAspect aspect = new ToolAuditAspect();
        AuditedFixture fixture = new AuditedFixture();
        AspectJProxyFactory factory = new AspectJProxyFactory(fixture);
        factory.addAspect(aspect);
        AuditedFixture proxied = factory.getProxy();

        assertThat(proxied.call()).isEqualTo(ToolResponses.ok("done"));
        assertThatThrownBy(proxied::fail).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void audit_noArgTool_keepsTheEmptyArgumentListShape() {
        AuditedFixture proxied = proxiedFixture();
        ListAppender<ILoggingEvent> appender = attachToAspectLogger();
        try {
            assertThat(proxied.call()).isEqualTo(ToolResponses.ok("done"));

            String record = singleToolRecord(appender);
            assertThat(record).contains("MCP tool 'fixture_tool'", "args=[]", "outcome=ok");
        } finally {
            detach(appender);
        }
    }

    @Test
    void audit_redactsCredentialArgument_andKeepsNonSensitiveSibling_inTheSameRecord() {
        AuditedFixture proxied = proxiedFixture();
        ListAppender<ILoggingEvent> appender = attachToAspectLogger();
        try {
            assertThat(proxied.withCredential(SYNTHETIC_TOKEN, NON_SENSITIVE))
                    .isEqualTo(ToolResponses.ok(NON_SENSITIVE));

            String record = singleToolRecord(appender);
            // Negative control and positive control on the SAME record: the credential
            // parameter is gone, the sibling value is still visible.
            assertThat(record).contains("[redacted]");
            assertThat(record).doesNotContain(SYNTHETIC_TOKEN);
            assertThat(record).contains(NON_SENSITIVE);
            assertThat(record).contains("MCP tool 'fixture_tool_credential'", "outcome=ok");
        } finally {
            detach(appender);
        }
    }

    @Test
    void audit_redactsEverySensitiveParameterNameVariant_andKeepsSiblings() {
        AuditedFixture proxied = proxiedFixture();
        ListAppender<ILoggingEvent> appender = attachToAspectLogger();
        String[] values = {
                "qcp_test_sensitive_01", "qcp_test_sensitive_02", "qcp_test_sensitive_03",
                "qcp_test_sensitive_04", "qcp_test_sensitive_05", "qcp_test_sensitive_06",
                "qcp_test_sensitive_07", "qcp_test_sensitive_08"
        };
        try {
            // Parameters (in order): apiKey, api_key, token, secret, pat, password,
            // credential, authorization, region.
            assertThat(proxied.withAllSensitiveNames(values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7], NON_SENSITIVE))
                    .isEqualTo(ToolResponses.ok(NON_SENSITIVE));

            String record = singleToolRecord(appender);
            assertThat(record).contains(NON_SENSITIVE);
            for (String value : values) {
                assertThat(record).doesNotContain(value);
            }
            // One redaction marker per sensitive parameter — proves every configured name
            // matched (a single match would leave the others in the clear).
            assertThat(occurrences(record, "[redacted]")).isEqualTo(values.length);
        } finally {
            detach(appender);
        }
    }

    @Test
    void audit_errorPath_redactsCredentialArgument_andKeepsOutcomeShape() {
        AuditedFixture proxied = proxiedFixture();
        ListAppender<ILoggingEvent> appender = attachToAspectLogger();
        try {
            assertThatThrownBy(() -> proxied.failWithToken(SYNTHETIC_TOKEN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            String record = singleToolRecord(appender);
            assertThat(record).contains("[redacted]", "outcome=error", "error=boom");
            assertThat(record).doesNotContain(SYNTHETIC_TOKEN);
        } finally {
            detach(appender);
        }
    }

    @Test
    void audit_hidesArgumentsEntirely_whenParameterNamesAreNull() throws Throwable {
        // Fail closed: no parameter names means no way to tell which argument is a
        // credential, so raw values must never be logged.
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{SYNTHETIC_TOKEN});
        when(signature.getParameterNames()).thenReturn(null);
        when(joinPoint.proceed()).thenReturn("ok");
        Tool tool = AuditedFixture.class
                .getMethod("withCredential", String.class, String.class).getAnnotation(Tool.class);

        ToolAuditAspect aspect = new ToolAuditAspect();
        ListAppender<ILoggingEvent> appender = attachToAspectLogger();
        try {
            assertThat(aspect.audit(joinPoint, tool)).isEqualTo("ok");

            String record = singleToolRecord(appender);
            assertThat(record).contains("[args hidden: parameter names unavailable]");
            assertThat(record).doesNotContain(SYNTHETIC_TOKEN);
        } finally {
            detach(appender);
        }
    }

    @Test
    void audit_hidesArgumentsEntirely_whenParameterNameCountMismatches() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{SYNTHETIC_TOKEN, NON_SENSITIVE});
        when(signature.getParameterNames()).thenReturn(new String[]{"apiKey"});
        when(joinPoint.proceed()).thenReturn("ok");
        Tool tool = AuditedFixture.class
                .getMethod("withCredential", String.class, String.class).getAnnotation(Tool.class);

        ToolAuditAspect aspect = new ToolAuditAspect();
        ListAppender<ILoggingEvent> appender = attachToAspectLogger();
        try {
            assertThat(aspect.audit(joinPoint, tool)).isEqualTo("ok");

            String record = singleToolRecord(appender);
            assertThat(record).contains("[args hidden: parameter names unavailable]");
            assertThat(record).doesNotContain(SYNTHETIC_TOKEN, NON_SENSITIVE);
        } finally {
            detach(appender);
        }
    }

    // ---- helpers ----

    private static AuditedFixture proxiedFixture() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new AuditedFixture());
        factory.addAspect(new ToolAuditAspect());
        return factory.getProxy();
    }

    private static ListAppender<ILoggingEvent> attachToAspectLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(ToolAuditAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ToolAuditAspect.class)).detachAppender(appender);
    }

    /** The single audit record produced by one tool invocation. */
    private static String singleToolRecord(ListAppender<ILoggingEvent> appender) {
        List<ILoggingEvent> records = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("MCP tool"))
                .toList();
        assertThat(records).hasSize(1);
        return records.get(0).getFormattedMessage();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
