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
}
