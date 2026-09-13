package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring wiring for {@link GitBranchService}. The token is resolved through
 * {@link PackCredentialService}, so a credential stored in the git tool pack by
 * an operator is authoritative; the service still falls back to the
 * {@code GITHUB_TOKEN} environment variable, which keeps CI and existing .env
 * files working. When nothing resolves, the bean is a no-op variant that logs a
 * warning and throws a clear {@link GitBranchException} on every operation, so
 * non-GitHub environments still boot. Callers check
 * {@link GitBranchService#isAvailable()} rather than catching that exception.
 */
@Configuration
@Slf4j
public class GitBranchConfig {

    static final String GIT_PACK_ID = "pack-git-0001";
    static final String GITHUB_TOKEN_KEY = "GITHUB_TOKEN";
    /**
     * Transitional alias. The startup scripts warned on {@code GH_TOKEN} and
     * {@code scripts/sdd-mcp-e2e.mjs} still gates on it, so environments in the wild
     * are as likely to export {@code GH_TOKEN} as {@code GITHUB_TOKEN}. Resolving the
     * alias through the same service keeps one resolution path while those setups
     * migrate. Remove once nothing exports the old name.
     */
    static final String DEPRECATED_GH_TOKEN_KEY = "GH_TOKEN";

    @Bean
    public GitBranchService gitBranchService(PackCredentialService credentialService) {
        String token = credentialService.resolve(GIT_PACK_ID, null, GITHUB_TOKEN_KEY);
        boolean fromAlias = false;
        if (token == null || token.isBlank()) {
            token = credentialService.resolve(GIT_PACK_ID, null, DEPRECATED_GH_TOKEN_KEY);
            fromAlias = token != null && !token.isBlank();
        }
        if (token == null || token.isBlank()) {
            log.warn("No GitHub credential resolved from the git tool pack ({}) or GITHUB_TOKEN: "
                    + "GitBranchService is disabled — Git branch operations will throw "
                    + "GitBranchException when invoked", GIT_PACK_ID);
            return new GitBranchService("") {
                private GitBranchException disabled() {
                    return new GitBranchException(0,
                            "No GitHub credential configured; Git branch operations are disabled. "
                                    + "Store a GITHUB_TOKEN in the git tool pack or set the "
                                    + "GITHUB_TOKEN environment variable.");
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
        if (fromAlias) {
            log.warn("GitHub credential resolved from the deprecated GH_TOKEN name; "
                    + "rename it to GITHUB_TOKEN");
        }
        return new GitBranchService(token);
    }
}
