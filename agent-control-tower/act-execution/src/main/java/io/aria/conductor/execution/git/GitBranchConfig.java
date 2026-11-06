package io.aria.conductor.execution.git;

import io.aria.conductor.common.git.GitCredentialGuidance;
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
 *
 * <p>A credential row that cannot be decrypted degrades to that same disabled
 * variant with a warning (pack id and credential key only, never the value):
 * the bean is eager, so letting the failure escape would abort context startup
 * instead of disabling git operations.
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
     *
     * <p>Precedence is per key, not global: {@link #GITHUB_TOKEN_KEY} is resolved first
     * <em>including its own {@code GITHUB_TOKEN} environment fallback</em>, and the alias is
     * consulted only when that canonical lookup yields nothing. A legacy credential stored in
     * the pack under {@code GH_TOKEN} therefore loses to an exported {@code GITHUB_TOKEN}, so
     * the spec's store-over-env guarantee holds only within a given key name.
     */
    static final String DEPRECATED_GH_TOKEN_KEY = "GH_TOKEN";

    @Bean
    public GitBranchService gitBranchService(PackCredentialService credentialService) {
        String token;
        boolean fromAlias = false;
        String resolvingKey = GITHUB_TOKEN_KEY;
        try {
            token = credentialService.resolve(GIT_PACK_ID, null, GITHUB_TOKEN_KEY);
            if (token == null || token.isBlank()) {
                resolvingKey = DEPRECATED_GH_TOKEN_KEY;
                token = credentialService.resolve(GIT_PACK_ID, null, DEPRECATED_GH_TOKEN_KEY);
                fromAlias = token != null && !token.isBlank();
            }
        } catch (RuntimeException e) {
            // A stored credential row that cannot be decrypted — a blank or foreign value, or
            // every row after a PACK_CREDENTIAL_KEY rotation — must not abort startup: this bean
            // is created eagerly, so a throwing resolve would take the whole backend down. Degrade
            // to the disabled stub exactly as the "nothing resolved" case does. Only the pack id
            // and the credential key are logged; the value never is.
            log.warn("Could not resolve the GitHub credential for pack {} (key {}) — {}: {}. "
                    + "GitBranchService is disabled; Git branch operations will throw "
                    + "GitBranchException when invoked",
                    GIT_PACK_ID, resolvingKey, e.getClass().getSimpleName(), e.getMessage());
            return disabledStub();
        }
        if (token == null || token.isBlank()) {
            log.warn("No GitHub credential resolved from the git tool pack ({}) or GITHUB_TOKEN: "
                    + "GitBranchService is disabled — Git branch operations will throw "
                    + "GitBranchException when invoked", GIT_PACK_ID);
            return disabledStub();
        }
        if (fromAlias) {
            log.warn("GitHub credential resolved from the deprecated GH_TOKEN name; "
                    + "rename it to GITHUB_TOKEN");
        }
        return new GitBranchService(token);
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
