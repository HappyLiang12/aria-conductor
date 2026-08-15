package io.aria.conductor.execution.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring wiring for {@link GitBranchService}. Reads {@code GH_TOKEN} (blank when
 * unset). When the token is absent the bean is a no-op variant that logs a
 * warning and throws a clear {@link GitBranchException} on every operation, so
 * non-GitHub environments still boot and failures surface loudly at call time
 * instead of as a startup crash.
 */
@Configuration
@Slf4j
public class GitBranchConfig {

    @Bean
    public GitBranchService gitBranchService(@Value("${GH_TOKEN:}") String ghToken) {
        if (ghToken == null || ghToken.isBlank()) {
            log.warn("GH_TOKEN is not configured: GitBranchService is disabled — "
                    + "Git branch operations will throw GitBranchException when invoked");
            return new GitBranchService("") {
                private GitBranchException disabled() {
                    return new GitBranchException(0,
                            "GH_TOKEN is not configured; Git branch operations are disabled");
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
        return new GitBranchService(ghToken);
    }
}
