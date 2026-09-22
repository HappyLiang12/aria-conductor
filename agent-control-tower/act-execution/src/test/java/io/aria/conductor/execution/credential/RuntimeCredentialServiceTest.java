package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.RuntimeCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.RuntimeCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link RuntimeCredentialService} (spec 6.1): required-key
 * authenticated encryption with a fresh nonce, core/reference binding as
 * authenticated data, masked reads, operator-only management and revocation of
 * future resolution. Synthetic secrets and a fixed clock only.
 */
class RuntimeCredentialServiceTest {

    private static final String SECRET = "synthetic-secret-42";
    private static final String KEY = "test-runtime-credential-key";
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REF = RuntimeCredentialService.QODER_CREDENTIAL_REFERENCE;

    private final RuntimeCredentialRepository repository = mock(RuntimeCredentialRepository.class);
    private final RuntimeCredentialService service =
            new RuntimeCredentialService(repository, KEY, CLOCK);

    private static ActorPrincipal operator() {
        return ActorPrincipal.operator(null);
    }

    private static ActorPrincipal worker() {
        return ActorPrincipal.worker(UUID.randomUUID(), NOW.plusSeconds(300));
    }

    /** Stores one credential through the service and returns the persisted row. */
    private RuntimeCredential store() {
        when(repository.findById(REF)).thenReturn(Optional.empty());
        service.putQoder(SECRET, operator());
        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void resolveReturnsTheSecretOnlyInTheChildEnvironmentBundle() {
        RuntimeCredential row = store();
        when(repository.findById(REF)).thenReturn(Optional.of(row));

        SecretBundle bundle = service.resolve(REF);

        assertThat(bundle.reference()).isEqualTo(REF);
        assertThat(bundle.environment())
                .containsExactly(java.util.Map.entry(
                        RuntimeCredentialService.QODER_ENVIRONMENT_VARIABLE, SECRET));
        assertThat(bundle.toString())
                .isEqualTo("SecretBundle[redacted]")
                .doesNotContain(SECRET);
    }

    @Test
    void putQoderPersistsOnlyCiphertextAndReturnsExactMaskedMetadata() {
        when(repository.findById(REF)).thenReturn(Optional.empty());
        RuntimeCredentialService.MaskedMetadata masked = service.putQoder(SECRET, operator());
        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(repository).save(captor.capture());
        RuntimeCredential row = captor.getValue();

        assertThat(row.getCredentialRef()).isEqualTo(REF);
        assertThat(row.getCoreId()).isEqualTo(RuntimeCredentialService.QODER_CORE_ID);
        assertThat(row.getEnvironmentVariable())
                .isEqualTo(RuntimeCredentialService.QODER_ENVIRONMENT_VARIABLE);
        // Neither the plaintext nor a plain base64 encoding of it may be persisted.
        assertThat(row.getEncValue()).isNotEqualTo(SECRET);
        assertThat(row.getEncValue()).isNotEqualTo(
                Base64.getEncoder().encodeToString(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);

        assertThat(masked.credentialRef()).isEqualTo(REF);
        assertThat(masked.coreId()).isEqualTo(RuntimeCredentialService.QODER_CORE_ID);
        assertThat(masked.environmentVariable())
                .isEqualTo(RuntimeCredentialService.QODER_ENVIRONMENT_VARIABLE);
        assertThat(masked.configured()).isTrue();
        assertThat(masked.maskedSecret()).isEqualTo("********");
        assertThat(masked.updatedAt()).isEqualTo(NOW);
        assertThat(String.valueOf(masked)).doesNotContain(SECRET);
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        when(repository.findById(REF)).thenReturn(Optional.empty());

        service.putQoder(SECRET, operator());
        service.putQoder(SECRET, operator());

        ArgumentCaptor<RuntimeCredential> captor = ArgumentCaptor.forClass(RuntimeCredential.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getEncValue())
                .as("a reused nonce would produce identical ciphertexts for identical plaintext")
                .isNotEqualTo(captor.getAllValues().get(1).getEncValue());
    }

    @Test
    void tamperedCiphertextIsRejected() {
        RuntimeCredential row = store();
        String ciphertext = row.getEncValue();
        char flipped = ciphertext.charAt(ciphertext.length() / 2) == 'A' ? 'B' : 'A';
        row.setEncValue(ciphertext.substring(0, ciphertext.length() / 2) + flipped
                + ciphertext.substring(ciphertext.length() / 2 + 1));
        when(repository.findById(REF)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.resolve(REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void ciphertextMovedToAnotherReferenceFailsToDecrypt() {
        RuntimeCredential row = store();
        // The row's reference is authenticated data: moving the same ciphertext under a
        // different reference must fail rather than return the old secret.
        row.setCredentialRef("qoder:rotated");
        when(repository.findById("qoder:rotated")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.resolve("qoder:rotated"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void missingEncryptionKeyFailsLoudlyAndNeverFallsBack() {
        RuntimeCredentialService withoutKey =
                new RuntimeCredentialService(repository, "  ", CLOCK);
        RuntimeCredential stored = RuntimeCredential.builder()
                .credentialRef(REF)
                .coreId(RuntimeCredentialService.QODER_CORE_ID)
                .environmentVariable(RuntimeCredentialService.QODER_ENVIRONMENT_VARIABLE)
                .encValue("dW50b3VjaGVk")
                .createdAt(NOW)
                .build();
        when(repository.findById(REF)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> withoutKey.putQoder(SECRET, operator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption key is not configured");
        assertThatThrownBy(() -> withoutKey.resolve(REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption key is not configured");
        verify(repository, never()).save(any(RuntimeCredential.class));
    }

    @Test
    void workerAndUnownedActorsCannotManageTheCredential() {
        assertThatThrownBy(() -> service.putQoder(SECRET, worker()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Operator authority required");
        assertThatThrownBy(() -> service.deleteQoder(worker()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Operator authority required");
        assertThatThrownBy(() -> service.putQoder(SECRET, null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.deleteQoder(null))
                .isInstanceOf(SecurityException.class);
        verify(repository, never()).save(any(RuntimeCredential.class));
        verify(repository, never()).deleteById(anyString());
    }

    @Test
    void deleteQoderRevokesFutureResolution() {
        RuntimeCredential row = store();
        when(repository.findById(REF)).thenReturn(Optional.of(row));

        service.deleteQoder(operator());

        verify(repository).deleteById(REF);
        when(repository.findById(REF)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(REF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void metadataReadNeverDecryptsAndReportsUnconfiguredExactly() {
        assertThat(service.qoderMetadata().configured()).isFalse();
        assertThat(service.qoderMetadata().maskedSecret()).isNull();
        assertThat(service.qoderMetadata().credentialRef()).isEqualTo(REF);

        RuntimeCredential row = store();
        when(repository.findById(REF)).thenReturn(Optional.of(row));
        RuntimeCredentialService.MaskedMetadata masked = service.qoderMetadata();

        assertThat(masked.configured()).isTrue();
        assertThat(masked.maskedSecret()).isEqualTo("********");
        assertThat(masked.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void blankSecretIsRejected() {
        assertThatThrownBy(() -> service.putQoder("  ", operator()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        verify(repository, never()).save(any(RuntimeCredential.class));
    }

    @Test
    void authenticatedDataIsUnambiguousAcrossComponentPairs() {
        // A plain "core:ref" concatenation would let ("a", "b:c") and ("a:b", "c")
        // share one AAD, so a ciphertext could be rebound between references whose
        // separator moved. Length prefixes make the components re-parse uniquely.
        assertThat(RuntimeCredentialService.authenticatedData("a", "b:c"))
                .as("two different pairs must never produce the same authenticated data")
                .isNotEqualTo(RuntimeCredentialService.authenticatedData("a:b", "c"));
        assertThat(RuntimeCredentialService.authenticatedData(
                RuntimeCredentialService.QODER_CORE_ID, REF))
                .contains(RuntimeCredentialService.QODER_CORE_ID)
                .contains(REF);
    }

    @Test
    void metadataReportsAStoredCredentialAsUnreadableWhenTheEncryptionKeyIsMissing() {
        RuntimeCredential row = store();
        when(repository.findById(REF)).thenReturn(Optional.of(row));

        assertThat(service.qoderMetadata().encryptionKeyConfigured()).isTrue();

        RuntimeCredentialService withoutKey = new RuntimeCredentialService(repository, "  ", CLOCK);
        RuntimeCredentialService.MaskedMetadata metadata = withoutKey.qoderMetadata();

        assertThat(metadata.configured()).isTrue();
        assertThat(metadata.encryptionKeyConfigured()).isFalse();
        assertThat(metadata.maskedSecret()).isEqualTo("********");
        // Reporting the unreadable state never repairs it: reading still fails loudly.
        assertThatThrownBy(() -> withoutKey.resolve(REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption key is not configured");
    }
}
