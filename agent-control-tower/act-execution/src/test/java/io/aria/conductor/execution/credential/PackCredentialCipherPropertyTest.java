package io.aria.conductor.execution.credential;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F property-based tests for {@link PackCredentialCipher} (AES-GCM at rest).
 *
 * <p>Round-trip: decrypt(encrypt(s)) == s for arbitrary printable/unicode strings
 * including empty and multi-KB inputs (generated chars stay outside the surrogate
 * range because a lone surrogate is already lossy at the String→UTF-8 boundary,
 * which is not a cipher property). Tampering: flipping any single bit of any byte
 * of the Base64-decoded IV+ciphertext blob must make decrypt fail or return
 * something different from the plaintext — never silently the same value.
 *
 * <p>Ciphers are static: key derivation is PBKDF2 with 100k iterations, far too
 * expensive to repeat per jqwik try.
 */
class PackCredentialCipherPropertyTest {

    private static final PackCredentialCipher AES_CIPHER =
            new PackCredentialCipher("phase-f-property-test-key");
    private static final PackCredentialCipher DEV_CIPHER =
            new PackCredentialCipher(""); // no key -> documented Base64 dev fallback

    // ── round-trip: encrypt then decrypt is the identity ─────────────────

    @Property(tries = 300)
    void aesRoundTripIsIdentityForArbitraryUnicodeStrings(@ForAll("unicodeStrings") String plaintext) {
        String encrypted = AES_CIPHER.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext).isBase64();
        assertThat(AES_CIPHER.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Property(tries = 20)
    void aesRoundTripIsIdentityForLongStrings(@ForAll @IntRange(min = 1_000, max = 8_000) int length,
                                              @ForAll("unicodeChars") Character filler) {
        String plaintext = String.valueOf(filler).repeat(length);

        assertThat(AES_CIPHER.decrypt(AES_CIPHER.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Property(tries = 100)
    void devModeBase64RoundTripIsIdentity(@ForAll("unicodeStrings") String plaintext) {
        String encoded = DEV_CIPHER.encrypt(plaintext);

        assertThat(encoded)
                .isEqualTo(Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8)));
        assertThat(DEV_CIPHER.decrypt(encoded)).isEqualTo(plaintext);
    }

    // ── tampering: any bit flip is never silently accepted ───────────────

    @Property(tries = 300)
    void flippingAnyBitOfCiphertextNeverSilentlyYieldsThePlaintext(
            @ForAll("unicodeStrings") String plaintext,
            @ForAll @IntRange(min = 0, max = 100_000) int byteIndexSeed,
            @ForAll @IntRange(min = 0, max = 7) int bitToFlip) {
        byte[] blob = Base64.getDecoder().decode(AES_CIPHER.encrypt(plaintext));
        int index = byteIndexSeed % blob.length; // blob >= IV(12) + tag(16), never empty
        blob[index] = (byte) (blob[index] ^ (1 << bitToFlip));
        String tampered = Base64.getEncoder().encodeToString(blob);

        String decrypted;
        try {
            decrypted = AES_CIPHER.decrypt(tampered);
        } catch (RuntimeException e) {
            // The documented failure mode: decrypt wraps the GCM auth failure.
            assertThat(e).hasMessageContaining("Credential decryption failed");
            return;
        }
        // If decryption somehow did not reject it, the output must not equal the plaintext.
        assertThat(decrypted).isNotEqualTo(plaintext);
    }

    @Property(tries = 300)
    void encryptionIsRandomizedPerCall(@ForAll("unicodeStrings") String plaintext) {
        // Fresh IV per call: identical plaintexts must never produce identical blobs.
        assertThat(AES_CIPHER.encrypt(plaintext)).isNotEqualTo(AES_CIPHER.encrypt(plaintext));
    }

    // ---- generators ----

    @Provide
    Arbitrary<String> unicodeStrings() {
        // Printable ASCII plus BMP unicode, excluding the surrogate block; empty allowed.
        return Arbitraries.strings()
                .withCharRange('\u0020', '\ud7ff')
                .withCharRange('\ue000', '\ufffd')
                .ofMinLength(0)
                .ofMaxLength(500);
    }

    @Provide
    Arbitrary<Character> unicodeChars() {
        return Arbitraries.chars().range('\u0020', '\ud7ff').range('\ue000', '\ufffd');
    }
}
