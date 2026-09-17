package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.RuntimeCredential;
import io.aria.conductor.common.repository.RuntimeCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour + security tests for {@link RuntimeCredentialService} — the DB-backed store for
 * runtime provider credentials (Qoder PAT). The cipher is mocked for the wiring tests so
 * assertions pin the service's contract (encrypt-on-save, decrypt-on-read, refusal of the
 * Base64 development fallback, typed failures) rather than crypto internals; one test uses a
 * real {@link PackCredentialCipher} to prove the persisted value is genuine ciphertext.
 *
 * <p>All tokens are synthetic ({@code pat-synthetic-1234}); no real credential is ever used.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeCredentialServiceTest {

    private static final String PROVIDER = "qoder";
    private static final String SYNTHETIC_PAT = "pat-synthetic-1234";
    private static final String CIPHER_KEY = "test-master-key-for-runtime-credentials";

    @Mock private RuntimeCredentialRepository credentialRepo;
    @Mock private PackCredentialCipher cipher;

    private RuntimeCredentialService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeCredentialService(credentialRepo, cipher);
    }

    private RuntimeCredential row(String encPat) {
        return RuntimeCredential.builder()
                .id(UUID.randomUUID().toString())
                .providerId(PROVIDER)
                .encPat(encPat)
                .updatedAt(Instant.parse("2026-09-17T10:15:30Z"))
                .build();
    }

    @Test
    void save_persistsOnlyCipherOutput_forNewRow() {
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.empty());
        when(cipher.encrypt(SYNTHETIC_PAT)).thenReturn("opaque-cipher-output");

        service.save(PROVIDER, SYNTHETIC_PAT);

        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(credentialRepo).save(captor.capture());
        RuntimeCredential saved = captor.getValue();
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getProviderId()).isEqualTo(PROVIDER);
        assertThat(saved.getEncPat()).isEqualTo("opaque-cipher-output");
        assertThat(saved.getEncPat()).isNotEqualTo(SYNTHETIC_PAT);
    }

    @Test
    void save_withRealCipher_storesCiphertext_thatDecryptsBackToThePat() {
        PackCredentialCipher realCipher = new PackCredentialCipher(CIPHER_KEY);
        RuntimeCredentialService realService = new RuntimeCredentialService(credentialRepo, realCipher);
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.empty());

        realService.save(PROVIDER, SYNTHETIC_PAT);

        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(credentialRepo).save(captor.capture());
        String encPat = captor.getValue().getEncPat();
        // Not the plaintext, not a plaintext-containing encoding, and actually decryptable.
        assertThat(encPat).isNotEqualTo(SYNTHETIC_PAT).doesNotContain(SYNTHETIC_PAT);
        assertThat(realCipher.decrypt(encPat)).isEqualTo(SYNTHETIC_PAT);
    }

    @Test
    void save_updatesExistingRow_inPlace_keepingItsId() {
        RuntimeCredential existing = row("enc-old");
        String originalId = existing.getId();
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.of(existing));
        when(cipher.encrypt(SYNTHETIC_PAT)).thenReturn("enc-new");

        service.save(PROVIDER, SYNTHETIC_PAT);

        verify(credentialRepo).save(existing);
        assertThat(existing.getId()).isEqualTo(originalId);
        assertThat(existing.getEncPat()).isEqualTo("enc-new");
    }

    @Test
    void save_rejectsNullPat_beforeTouchingCipherOrRepository() {
        assertThatThrownBy(() -> service.save(PROVIDER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SYNTHETIC_PAT);
        verifyNoInteractions(credentialRepo, cipher);
    }

    @Test
    void save_rejectsBlankPat_andDoesNotEchoTheInput() {
        String blankInput = "   ";
        assertThatThrownBy(() -> service.save(PROVIDER, blankInput))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(blankInput);
        verifyNoInteractions(credentialRepo, cipher);
    }

    @Test
    void save_refusesBase64Fallback_whenEncryptionDisabled() {
        when(cipher.encryptionEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.save(PROVIDER, SYNTHETIC_PAT))
                .isInstanceOf(RuntimeCredentialException.class)
                .satisfies(e -> assertThat(((RuntimeCredentialException) e).cause())
                        .isEqualTo(RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED));

        verify(cipher, never()).encrypt(any());
        verify(credentialRepo, never()).save(any());
    }

    @Test
    void configured_trueWithRow_falseWithout_neverTouchingTheCipher() {
        when(credentialRepo.findByProviderId(PROVIDER))
                .thenReturn(Optional.of(row("enc-1")))
                .thenReturn(Optional.empty());

        assertThat(service.configured(PROVIDER)).isTrue();
        assertThat(service.configured(PROVIDER)).isFalse();
        verifyNoInteractions(cipher);
    }

    @Test
    void read_returnsDecryptedPat_whenConfigured() {
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.of(row("enc-stored")));
        when(cipher.decrypt("enc-stored")).thenReturn(SYNTHETIC_PAT);

        // The cipher stub output differs from the raw column value, so this cannot pass
        // by returning the stored ciphertext.
        assertThat(service.read(PROVIDER)).isEqualTo(SYNTHETIC_PAT);
    }

    @Test
    void read_refusesBase64Fallback_whenEncryptionDisabled() {
        when(cipher.encryptionEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.read(PROVIDER))
                .isInstanceOf(RuntimeCredentialException.class)
                .satisfies(e -> assertThat(((RuntimeCredentialException) e).cause())
                        .isEqualTo(RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED));

        verify(cipher, never()).decrypt(any());
    }

    @Test
    void read_throwsNotConfigured_whenNoRow() {
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(PROVIDER))
                .isInstanceOf(RuntimeCredentialException.class)
                .satisfies(e -> assertThat(((RuntimeCredentialException) e).cause())
                        .isEqualTo(RuntimeCredentialException.Cause.NOT_CONFIGURED));
    }

    @Test
    void read_throwsCipherFailed_whenDecryptFails_withoutEchoingCiphertext() {
        RuntimeException cipherFailure = new RuntimeException("Credential decryption failed");
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.of(row("enc-corrupt")));
        when(cipher.decrypt("enc-corrupt")).thenThrow(cipherFailure);

        assertThatThrownBy(() -> service.read(PROVIDER))
                .isInstanceOf(RuntimeCredentialException.class)
                .hasCause(cipherFailure)
                .hasMessageNotContaining("enc-corrupt")
                .hasMessageNotContaining(SYNTHETIC_PAT)
                .satisfies(e -> assertThat(((RuntimeCredentialException) e).cause())
                        .isEqualTo(RuntimeCredentialException.Cause.CIPHER_FAILED));
    }

    @Test
    void delete_removesRow_andIsIdempotentWhenAbsent() {
        RuntimeCredential existing = row("enc-1");
        when(credentialRepo.findByProviderId(PROVIDER))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty());

        service.delete(PROVIDER);
        service.delete(PROVIDER);

        verify(credentialRepo).delete(existing);
        verify(credentialRepo, times(1)).delete(any(RuntimeCredential.class));
    }

    @Test
    void maskedStatus_masksAllButTheLastFourChars_whenConfigured() {
        RuntimeCredential existing = row("enc-stored");
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.of(existing));
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(cipher.decrypt("enc-stored")).thenReturn(SYNTHETIC_PAT);

        RuntimeCredentialStatus status = service.maskedStatus(PROVIDER);

        assertThat(status.configured()).isTrue();
        assertThat(status.patMasked()).isEqualTo("****1234").doesNotContain(SYNTHETIC_PAT);
        assertThat(status.updatedAt()).isEqualTo(existing.getUpdatedAt());
    }

    @Test
    void maskedStatus_returnsBareMask_forShortOrMissingPat() {
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.of(row("enc-short")));
        when(cipher.encryptionEnabled()).thenReturn(true);
        when(cipher.decrypt("enc-short")).thenReturn("ab").thenReturn(null);

        assertThat(service.maskedStatus(PROVIDER).patMasked()).isEqualTo("****");
        assertThat(service.maskedStatus(PROVIDER).patMasked()).isEqualTo("****");
    }

    @Test
    void maskedStatus_reportsUnconfigured_whenNoRow_evenIfEncryptionIsDisabled() {
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.empty());

        RuntimeCredentialStatus status = service.maskedStatus(PROVIDER);

        assertThat(status).isEqualTo(new RuntimeCredentialStatus(false, null, null));
        verifyNoInteractions(cipher);
    }

    @Test
    void maskedStatus_refusesBase64Fallback_whenRowExistsButEncryptionDisabled() {
        when(credentialRepo.findByProviderId(PROVIDER)).thenReturn(Optional.of(row("enc-1")));
        when(cipher.encryptionEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.maskedStatus(PROVIDER))
                .isInstanceOf(RuntimeCredentialException.class)
                .satisfies(e -> assertThat(((RuntimeCredentialException) e).cause())
                        .isEqualTo(RuntimeCredentialException.Cause.KEY_NOT_CONFIGURED));

        verify(cipher, never()).decrypt(any());
    }
}
