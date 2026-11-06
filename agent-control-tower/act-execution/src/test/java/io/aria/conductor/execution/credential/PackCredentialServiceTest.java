package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.PackCredential;
import io.aria.conductor.common.repository.PackCredentialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link PackCredentialService} — credential resolution precedence
 * (per-agent override → pack-global → host-env fallback) and encrypt-on-store. All stored/
 * resolved values pass through the {@link PackCredentialCipher}, which is mocked here to keep
 * the assertions about precedence and encryption wiring rather than crypto internals.
 */
@ExtendWith(MockitoExtension.class)
class PackCredentialServiceTest {

    @Mock private PackCredentialRepository credentialRepo;
    @Mock private PackCredentialCipher cipher;
    @InjectMocks private PackCredentialService service;

    private static final String PACK = "pack-git-0001";
    private static final String AGENT = "agent-1";

    private PackCredential cred(String enc) {
        return PackCredential.builder().id(UUID.randomUUID().toString())
                .packId(PACK).credKey("GITHUB_TOKEN").encValue(enc).build();
    }

    @Test
    void resolve_prefersPerAgentOverride_andSkipsGlobalLookup() {
        when(credentialRepo.findByPackIdAndAgentIdAndCredKey(PACK, AGENT, "GITHUB_TOKEN"))
                .thenReturn(Optional.of(cred("enc-agent")));
        when(cipher.decrypt("enc-agent")).thenReturn("agent-token");

        String result = service.resolve(PACK, AGENT, "GITHUB_TOKEN");

        assertThat(result).isEqualTo("agent-token");
        // Precedence: the pack-global lookup must not happen once the agent override hits.
        verify(credentialRepo, never()).findByPackIdAndAgentIdIsNullAndCredKey(any(), any());
    }

    @Test
    void resolve_fallsToPackGlobal_whenAgentOverrideAbsent() {
        when(credentialRepo.findByPackIdAndAgentIdAndCredKey(PACK, AGENT, "GITHUB_TOKEN"))
                .thenReturn(Optional.empty());
        when(credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(PACK, "GITHUB_TOKEN"))
                .thenReturn(Optional.of(cred("enc-global")));
        when(cipher.decrypt("enc-global")).thenReturn("global-token");

        assertThat(service.resolve(PACK, AGENT, "GITHUB_TOKEN")).isEqualTo("global-token");
    }

    @Test
    void resolve_skipsAgentLookup_whenAgentIdNull() {
        when(credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(PACK, "GITHUB_TOKEN"))
                .thenReturn(Optional.of(cred("enc-global")));
        when(cipher.decrypt("enc-global")).thenReturn("global-token");

        assertThat(service.resolve(PACK, null, "GITHUB_TOKEN")).isEqualTo("global-token");
        verify(credentialRepo, never()).findByPackIdAndAgentIdAndCredKey(any(), any(), any());
    }

    @Test
    void resolve_returnsNull_whenAbsentEverywhereAndNotInHostEnv() {
        // Use a key that is astronomically unlikely to be a real host env var.
        String phantomKey = "ARIA_PHANTOM_" + UUID.randomUUID().toString().replace("-", "");
        when(credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(PACK, phantomKey))
                .thenReturn(Optional.empty());

        assertThat(service.resolve(PACK, null, phantomKey)).isNull();
    }

    /**
     * Spec §7.5: a stored credential must win over the host environment. {@code System.getenv}
     * is not stubbable here (no mockito-inline mock-maker in this module), so the test uses a key
     * that certainly exists in the JVM environment — {@code PATH} — and asserts the STORED value
     * comes back. A resolver that consulted the environment before the store returns the host
     * PATH instead and fails this assertion.
     */
    @Test
    void resolve_prefersStoredCredential_overHostEnvironment() {
        String hostPath = System.getenv("PATH");
        assertThat(hostPath).as("PATH must exist for this precedence test to be meaningful").isNotNull();

        when(credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(PACK, "PATH"))
                .thenReturn(Optional.of(cred("enc-stored-path")));
        when(cipher.decrypt("enc-stored-path")).thenReturn("stored-wins");

        assertThat(service.resolve(PACK, null, "PATH")).isEqualTo("stored-wins");
        // Guards against a vacuous pass: the environment value is genuinely a different value.
        assertThat(hostPath).isNotEqualTo("stored-wins");
    }

    @Test
    void store_encryptsValueBeforePersisting_forNewCredential() {
        when(credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(PACK, "GITHUB_TOKEN"))
                .thenReturn(Optional.empty());
        when(cipher.encrypt("plaintext-token")).thenReturn("ENC(plaintext-token)");

        service.store(PACK, null, "GITHUB_TOKEN", "plaintext-token");

        ArgumentCaptor<PackCredential> captor = ArgumentCaptor.forClass(PackCredential.class);
        verify(credentialRepo).save(captor.capture());
        PackCredential saved = captor.getValue();
        assertThat(saved.getPackId()).isEqualTo(PACK);
        assertThat(saved.getCredKey()).isEqualTo("GITHUB_TOKEN");
        assertThat(saved.getAgentId()).isNull();
        // The raw value is never persisted — only the cipher output.
        assertThat(saved.getEncValue()).isEqualTo("ENC(plaintext-token)");
    }

    @Test
    void store_updatesExistingAgentCredential_inPlace() {
        PackCredential existing = cred("old-enc");
        when(credentialRepo.findByPackIdAndAgentIdAndCredKey(PACK, AGENT, "GITHUB_TOKEN"))
                .thenReturn(Optional.of(existing));
        when(cipher.encrypt("new-token")).thenReturn("ENC(new-token)");

        service.store(PACK, AGENT, "GITHUB_TOKEN", "new-token");

        verify(credentialRepo).save(eq(existing));
        assertThat(existing.getEncValue()).isEqualTo("ENC(new-token)");
    }
}
