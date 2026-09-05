package io.aria.conductor.app.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SecurityProperties} key parsing / matching (AC4, AC7).
 */
class SecurityPropertiesTest {

    private final SecurityProperties props = new SecurityProperties();

    @Test
    void defaultsToDisabledWithoutKeys() {
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.hasConfiguredKeys()).isFalse();
    }

    @Test
    void parsesCommaAndSpaceSeparatedKeys() {
        props.setApiKeys(" alpha ,beta\tgamma,delta epsilon ");
        assertThat(props.keys()).hasSize(5);
        assertThat(props.matches("alpha")).isTrue();
        assertThat(props.matches("beta")).isTrue();
        assertThat(props.matches("gamma")).isTrue();
        assertThat(props.matches("delta")).isTrue();
        assertThat(props.matches("epsilon")).isTrue();
    }

    @Test
    void matchingIsExactAndCaseSensitive() {
        props.setApiKeys("secret-key");
        assertThat(props.matches("secret-key")).isTrue();
        assertThat(props.matches("Secret-Key")).isFalse();
        assertThat(props.matches("secret-ke")).isFalse();
        assertThat(props.matches("secret-key ")).isFalse();
        assertThat(props.matches("")).isFalse();
        assertThat(props.matches(null)).isFalse();
    }

    @Test
    void acceptsSha256HashedEntryAndMatchesPreimage() {
        String preimage = "hashed-secret-123";
        String digest = sha256Hex(preimage);
        props.setApiKeys("sha256:" + digest);

        assertThat(props.hasConfiguredKeys()).isTrue();
        assertThat(props.keys()).hasSize(1);
        assertThat(props.keys().get(0).isHashed()).isTrue();
        assertThat(props.matches(preimage)).isTrue();
        assertThat(props.matches("wrong-key")).isFalse();
    }

    @Test
    void acceptsUppercaseShaPrefixAndHex() {
        String preimage = "another-secret";
        props.setApiKeys("SHA256:" + sha256Hex(preimage).toUpperCase());
        assertThat(props.matches(preimage)).isTrue();
    }

    @Test
    void supportsMixedPlaintextAndHashedEntries() {
        String hashed = sha256Hex("hash-key");
        props.setApiKeys("plain-key, sha256:" + hashed);
        assertThat(props.matches("plain-key")).isTrue();
        assertThat(props.matches("hash-key")).isTrue();
        assertThat(props.matches("nope")).isFalse();
    }

    @Test
    void rejectsMalformedSha256Entries() {
        props.setApiKeys("sha256:not-hex");
        assertThatThrownBy(props::keys)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256:");
        props.setApiKeys("sha256:abcdef"); // too short
        assertThatThrownBy(props::keys).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provisionablePlaintextKeyReturnsOnlyPlaintext() {
        props.setApiKeys("alpha,beta");
        assertThat(props.provisionablePlaintextKey()).hasValue("alpha");

        props.setApiKeys("sha256:" + sha256Hex("x") + ", gamma");
        assertThat(props.provisionablePlaintextKey()).hasValue("gamma");

        props.setApiKeys("sha256:" + sha256Hex("only-hash"));
        assertThat(props.provisionablePlaintextKey()).isEmpty();
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
