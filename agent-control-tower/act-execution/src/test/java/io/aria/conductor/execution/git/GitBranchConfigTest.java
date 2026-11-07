package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitBranchConfigTest {

    private final GitBranchConfig config = new GitBranchConfig();

    @Test
    void resolvesTokenFromTheCredentialStore() {
        PackCredentialService packCredentials = mock(PackCredentialService.class);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.GITHUB_TOKEN_KEY))
                .thenReturn("ghp_from_store");
        GitCredentialResolver resolver = new GitCredentialResolver(packCredentials);

        GitBranchService service = config.gitBranchService(resolver);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void fallsBackToTheDeprecatedGhTokenAlias() {
        PackCredentialService packCredentials = mock(PackCredentialService.class);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.GITHUB_TOKEN_KEY))
                .thenReturn(null);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.DEPRECATED_GH_TOKEN_KEY))
                .thenReturn("ghp_legacy");
        GitCredentialResolver resolver = new GitCredentialResolver(packCredentials);

        GitBranchService service = config.gitBranchService(resolver);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void returnsDisabledStubWhenNothingResolves() {
        PackCredentialService packCredentials = mock(PackCredentialService.class);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.GITHUB_TOKEN_KEY))
                .thenReturn(null);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.DEPRECATED_GH_TOKEN_KEY))
                .thenReturn(null);
        GitCredentialResolver resolver = new GitCredentialResolver(packCredentials);

        GitBranchService service = config.gitBranchService(resolver);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.createBranch("https://github.com/owner/repo.git", "b"))
                .isInstanceOf(GitBranchException.class);
    }

    /**
     * A stored credential row that cannot be decrypted (blank/foreign value, or a rotated
     * PACK_CREDENTIAL_KEY) used to throw out of this eagerly-created bean and abort context
     * startup. It must instead degrade to the disabled variant.
     */
    @Test
    void degradesToDisabledStubWhenResolveThrows() {
        PackCredentialService packCredentials = mock(PackCredentialService.class);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.GITHUB_TOKEN_KEY))
                .thenThrow(new RuntimeException("Credential decryption failed"));
        GitCredentialResolver resolver = new GitCredentialResolver(packCredentials);

        GitBranchService service = config.gitBranchService(resolver);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.createBranch("https://github.com/owner/repo.git", "b"))
                .isInstanceOf(GitBranchException.class);
    }

    /** Same degradation when the throwing credential row is only reached via the alias. */
    @Test
    void degradesToDisabledStubWhenTheAliasResolveThrows() {
        PackCredentialService packCredentials = mock(PackCredentialService.class);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.GITHUB_TOKEN_KEY))
                .thenReturn(null);
        when(packCredentials.resolve(GitCredentialResolver.GIT_PACK_ID, null,
                GitCredentialResolver.DEPRECATED_GH_TOKEN_KEY))
                .thenThrow(new RuntimeException("Credential decryption failed"));
        GitCredentialResolver resolver = new GitCredentialResolver(packCredentials);

        GitBranchService service = config.gitBranchService(resolver);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.getFile("https://github.com/owner/repo.git", "b", "p.md"))
                .isInstanceOf(GitBranchException.class)
                .hasMessageContaining("GITHUB_TOKEN");
    }
}
