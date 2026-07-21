package io.aria.conductor.execution.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM cipher for pack credential encryption at rest.
 * Key derived from env PACK_CREDENTIAL_KEY (32-byte hex or raw string padded to 32 bytes).
 * If no key is configured, falls back to Base64 encoding (development mode only).
 */
@Slf4j
@Component
public class PackCredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKeySpec secretKey;
    private final boolean encryptionEnabled;
    private final SecureRandom secureRandom = new SecureRandom();

    public PackCredentialCipher(@Value("${PACK_CREDENTIAL_KEY:}") String keyEnv) {
        if (keyEnv != null && !keyEnv.isBlank()) {
            byte[] keyBytes = deriveKey(keyEnv);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.encryptionEnabled = true;
            log.info("Pack credential encryption enabled (AES-GCM)");
        } else {
            this.secretKey = null;
            this.encryptionEnabled = false;
            log.warn("PACK_CREDENTIAL_KEY not set — credentials stored as Base64 (dev mode only)");
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (!encryptionEnabled) {
            return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Credential encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null) return null;
        if (!encryptionEnabled) {
            return new String(Base64.getDecoder().decode(encrypted), StandardCharsets.UTF_8);
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Credential decryption failed", e);
        }
    }

    private byte[] deriveKey(String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[32]; // AES-256
        System.arraycopy(raw, 0, result, 0, Math.min(raw.length, 32));
        return result;
    }
}
