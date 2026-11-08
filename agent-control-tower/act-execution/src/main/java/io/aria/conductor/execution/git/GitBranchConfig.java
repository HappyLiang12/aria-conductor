package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for {@link GitBranchService}. The token is resolved through
 * {@link PackCredentialService} by a {@link GitTokenSource}, so a credential
 * stored in the git tool pack by an operator is authoritative; the source still
 * falls back to the {@code GITHUB_TOKEN} environment variable, which keeps CI and
 * existing .env files working. A credential row that cannot be decrypted degrades
 * to "unavailable" with a warning (pack id and credential key only, never the
 * value), so the eager bean never aborts context startup.
 *
 * <p>Resolution happens per operation, not once while this eager bean is created
 * (F8): a credential stored through
 * {@code POST /api/v1/packs/pack-git-0001/credentials} used to need a restart.
 * The same source is exposed as a {@link GitTokenSource} bean, so the run-owned
 * launch environment injects the resolved credential into a core (F14) through the
 * one resolution path instead of a second copy of the precedence rules.
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

    /**
     * The one git-credential resolution path: per operation, pack-first, with the
     * host environment as the last fallback. Consumed by {@link GitBranchService}
     * and by the launch environment that injects the credential into a core.
     */
    @Bean
    public GitTokenSource gitTokenSource(PackCredentialService credentialService) {
        return new PackGitTokenSource(credentialService);
    }

    @Bean
    public GitBranchService gitBranchService(GitTokenSource gitTokenSource) {
        GitBranchService service = new GitBranchService(gitTokenSource);
        if (!service.isAvailable()) {
            log.warn("No GitHub credential resolves from the git tool pack ({}) or GITHUB_TOKEN yet."
                            + " Git branch operations refuse until one is stored; a credential stored through"
                            + " POST /api/v1/packs/{}/credentials is used by the next operation, with no restart",
                    GIT_PACK_ID, GIT_PACK_ID);
        }
        return service;
    }
}
