package io.aria.conductor.execution.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7: an ACP permission ask now surfaces the tool arguments to the operator, so
 * credential-shaped values must never reach the approval row or the API. The
 * redactor is deliberately conservative: it masks only recognizable secret
 * shapes and named secret keys, leaving ordinary tool input intact.
 */
class CredentialRedactorTest {

    @Test
    void returnsNullAndBlankUnchanged() {
        assertThat(CredentialRedactor.redact(null)).isNull();
        assertThat(CredentialRedactor.redact("")).isEmpty();
        assertThat(CredentialRedactor.redact("   ")).isEqualTo("   ");
    }

    @Test
    void leavesOrdinaryArgumentsIntact() {
        String arguments = "{\"path\":\"notes.txt\",\"content\":\"hello\",\"issueRef\":\"#81\"}";
        assertThat(CredentialRedactor.redact(arguments)).isEqualTo(arguments);
    }

    @Test
    void masksGitHubPersonalAccessTokens() {
        String arguments = "{\"cmd\":\"git clone https://ghp_abcdefghijklmnopqrstuvwxyz012345@github.com/o/r\"}";
        String redacted = CredentialRedactor.redact(arguments);
        assertThat(redacted).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz012345");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void masksFineGrainedGitHubTokens() {
        String redacted = CredentialRedactor.redact(
                "{\"token\":\"github_pat_11ABCDEFG0123456789_abcdefghijklmnop\"}");
        assertThat(redacted).doesNotContain("github_pat_11ABCDEFG");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void masksProviderApiKeysAndAwsKeys() {
        assertThat(CredentialRedactor.redact("sk-abcdefghijklmnop1234"))
                .isEqualTo("[REDACTED]");
        assertThat(CredentialRedactor.redact("AKIAIOSFODNN7EXAMPLE"))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void masksBearerValues() {
        String redacted = CredentialRedactor.redact("Authorization: Bearer abc.def-123456789");
        assertThat(redacted).contains("Bearer").contains("[REDACTED]");
        assertThat(redacted).doesNotContain("abc.def-123456789");
    }

    @Test
    void masksNamedSecretJsonValues() {
        String arguments = "{\"username\":\"me\",\"token\":\"abc123\",\"password\":\"hunter2\","
                + "\"api_key\":\"secret-value\",\"normal\":\"keep\"}";
        String redacted = CredentialRedactor.redact(arguments);
        assertThat(redacted)
                .doesNotContain("abc123")
                .doesNotContain("hunter2")
                .doesNotContain("secret-value")
                .contains("\"username\":\"me\"")
                .contains("\"normal\":\"keep\"");
    }

    @Test
    void redactionIsIdempotent() {
        String once = CredentialRedactor.redact("{\"token\":\"ghp_abcdefghijklmnopqrstuvwxyz012345\"}");
        assertThat(CredentialRedactor.redact(once)).isEqualTo(once);
    }
}
