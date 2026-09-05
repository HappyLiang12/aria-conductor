package io.aria.conductor.execution.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for {@link GitHubIssueClient}. Reads {@code GH_TOKEN}; when the token
 * is absent the bean is created token-less and every resolution attempt fails fast
 * with {@link IssueReferenceException} (an SDD spec task must never be dispatched
 * with an ungroundable issue reference), so non-GitHub environments still boot and
 * failures surface loudly at call time instead of as a startup crash.
 */
@Configuration
@Slf4j
public class GitHubIssueClientConfig {

    @Bean
    public GitHubIssueClient gitHubIssueClient(@Value("${GH_TOKEN:}") String ghToken) {
        if (ghToken == null || ghToken.isBlank()) {
            log.warn("GH_TOKEN is not configured: GitHubIssueClient is disabled — "
                    + "SDD spec-task issue grounding will fail fast when invoked");
        }
        return new GitHubIssueClient(ghToken);
    }
}
