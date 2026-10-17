package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.CoreCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.CoreCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreCredentialServiceTest {

    private static final String SECRET = "pat-secret-value-1234";

    private final CoreCredentialRepository repository = mock(CoreCredentialRepository.class);
    private final CoreCredentialService service =
            new CoreCredentialService(repository, Clock.systemUTC());

    @BeforeEach
    void saveReturnsWhatWasGiven() {
        when(repository.save(any())).then(returnsFirstArg());
    }

    @Test
    void resolveReturnsSecretBundleForTheFrozenReference() {
        org.mockito.ArgumentCaptor<CoreCredential> saved =
                org.mockito.ArgumentCaptor.forClass(CoreCredential.class);
        service.put(CoreCredentialService.QODER_CORE_ID, SECRET, ActorPrincipal.operator(null));
        verify(repository).save(saved.capture());
        when(repository.findById(CoreCredentialService.QODER_CORE_ID))
                .thenReturn(Optional.of(saved.getValue()));

        SecretBundle bundle = service.resolve("qoder:operator");

        assertThat(bundle.reference()).isEqualTo("qoder:operator");
        assertThat(bundle.environment())
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", SECRET);
        assertThat(bundle.toString()).doesNotContain(SECRET);
    }

    @Test
    void resolveOfMissingCredentialFailsAdmissionLoudly() {
        assertThatThrownBy(() -> service.resolve("qoder:operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime credential is not configured: qoder:operator");
    }

    @Test
    void metadataIsMaskedAndNeverCarriesTheValue() {
        org.mockito.ArgumentCaptor<CoreCredential> saved =
                org.mockito.ArgumentCaptor.forClass(CoreCredential.class);
        service.put(CoreCredentialService.QODER_CORE_ID, SECRET, ActorPrincipal.operator(null));
        verify(repository).save(saved.capture());
        when(repository.findById(CoreCredentialService.QODER_CORE_ID))
                .thenReturn(Optional.of(saved.getValue()));

        CoreCredentialService.MaskedMetadata metadata = service.metadata("qoder");

        assertThat(metadata.configured()).isTrue();
        assertThat(metadata.maskedSecret()).isEqualTo("****1234");
        assertThat(metadata.toString()).doesNotContain(SECRET);
    }

    @Test
    void putRejectsBlankValuesAndUnknownCores() {
        assertThatThrownBy(() -> service.put("qoder", "  ", ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put("opencode", SECRET, ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported core");
    }

    @Test
    void putAndDeleteRequireAnOperatorPrincipal() {
        assertThatThrownBy(() -> service.put("qoder", SECRET, null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.delete("qoder", null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deleteRemovesTheRow() {
        service.put(CoreCredentialService.QODER_CORE_ID, SECRET, ActorPrincipal.operator(null));

        service.delete(CoreCredentialService.QODER_CORE_ID, ActorPrincipal.operator(null));

        org.mockito.Mockito.verify(repository).deleteById("qoder");
    }
}
