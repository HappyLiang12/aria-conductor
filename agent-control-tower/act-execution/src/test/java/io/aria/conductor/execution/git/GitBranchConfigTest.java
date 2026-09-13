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
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn("ghp_from_store");

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void fallsBackToTheDeprecatedGhTokenAlias() {
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn(null);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null,
                GitBranchConfig.DEPRECATED_GH_TOKEN_KEY))
                .thenReturn("ghp_legacy");

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void returnsDisabledStubWhenNothingResolves() {
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn(null);

        GitBranchService service = config.gitBranchService(credentials);

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
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenThrow(new RuntimeException("Credential decryption failed"));

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.createBranch("https://github.com/owner/repo.git", "b"))
                .isInstanceOf(GitBranchException.class);
    }

    /** Same degradation when the throwing credential row is only reached via the alias. */
    @Test
    void degradesToDisabledStubWhenTheAliasResolveThrows() {
        PackCredentialService credentials = mock(PackCredentialService.class);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null, GitBranchConfig.GITHUB_TOKEN_KEY))
                .thenReturn(null);
        when(credentials.resolve(GitBranchConfig.GIT_PACK_ID, null,
                GitBranchConfig.DEPRECATED_GH_TOKEN_KEY))
                .thenThrow(new RuntimeException("Credential decryption failed"));

        GitBranchService service = config.gitBranchService(credentials);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.getFile("https://github.com/owner/repo.git", "b", "p.md"))
                .isInstanceOf(GitBranchException.class)
                .hasMessageContaining("GITHUB_TOKEN");
    }
}
