package io.aria.conductor.execution.git;

import io.aria.conductor.common.git.GitCredentialGuidance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring wiring for {@link GitBranchService}. The token is resolved through
 * {@link GitCredentialResolver}, which consults the pack credential store and
 * falls back to environment variables. When nothing resolves, the bean is a
 * no-op variant that logs a warning and throws a clear {@link GitBranchException}
 * on every operation, so non-GitHub environments still boot. Callers check
 * {@link GitBranchService#isAvailable()} rather than catching that exception.
 */
@Configuration
@Slf4j
public class GitBranchConfig {

    @Bean
    public GitBranchService gitBranchService(GitCredentialResolver credentialResolver) {
        Optional<String> token = credentialResolver.resolveToken();
        if (token.isEmpty()) {
            log.warn("No GitHub credential resolved from the git tool pack ({}) or GITHUB_TOKEN: "
                    + "GitBranchService is disabled — Git branch operations will throw "
                    + "GitBranchException when invoked", GitCredentialResolver.GIT_PACK_ID);
            return disabledStub();
        }
        return new GitBranchService(token.get());
    }

    /**
     * No-op variant used when no usable credential resolves. Every operation throws a
     * {@link GitBranchException} carrying the operator guidance, and
     * {@link GitBranchService#isAvailable()} reports false so callers can fail fast.
     */
    private static GitBranchService disabledStub() {
        return new GitBranchService("") {
            private GitBranchException disabled() {
                return new GitBranchException(0, GitCredentialGuidance.REQUIRED_MESSAGE);
            }

            @Override
            public void createBranch(String repoUrl, String branchName) {
                throw disabled();
            }

            @Override
            public void putFile(String repoUrl, String branchName, String path,
                                String content, String commitMessage) {
                throw disabled();
            }

            @Override
            public Optional<String> getFile(String repoUrl, String branchName, String path) {
                throw disabled();
            }

            @Override
            public Optional<String> branchHeadSha(String repoUrl, String branchName) {
                throw disabled();
            }
        };
    }
}
