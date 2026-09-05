package io.aria.conductor.execution.git;

/**
 * Raised when an SDD spec-task issue reference cannot be grounded before dispatch
 * (R9-F2). A spec-authoring sub-task must never be emitted with an unresolvable
 * issue handle or an un-substituted repository placeholder, so the dispatch layer
 * fails fast and surfaces an explicit error instead.
 *
 * <p>The user-facing message never distinguishes "not found" from "no permission"
 * (to avoid leaking repository/issue existence); that distinction is only kept in
 * the audit log via {@link #transientFailure()}.
 */
public class IssueReferenceException extends RuntimeException {

    private final String issueKey;
    private final String repository;
    private final int httpStatus;
    private final boolean transientFailure;

    public IssueReferenceException(String issueKey, String repository,
                                   int httpStatus, boolean transientFailure, String message) {
        super(message);
        this.issueKey = issueKey;
        this.repository = repository;
        this.httpStatus = httpStatus;
        this.transientFailure = transientFailure;
    }

    /** Issue key that could not be resolved (may be the original user/template-supplied key). */
    public String getIssueKey() {
        return issueKey;
    }

    /** {@code owner/repo} that was searched / fetched. */
    public String getRepository() {
        return repository;
    }

    /** Suggested HTTP status for the caller boundary (422 for unresolvable key, 503 for disabled, 502 transient). */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** True only for transient network/API failures (distinguished in logs, never in the user-facing error). */
    public boolean isTransientFailure() {
        return transientFailure;
    }

    /** The configured token cannot reach the repository (treated exactly like not-found for the caller). */
    public static IssueReferenceException disabled(String issueKey, String repository) {
        return new IssueReferenceException(issueKey, repository, 503, false,
                "Issue \"" + issueKey + "\" could not be resolved in " + repository
                        + "; GH_TOKEN is not configured for issue grounding.");
    }

    /** GitHub answered not-found / no-permission (401/403/404/410) or search had no match. */
    public static IssueReferenceException notFound(String issueKey, String repository) {
        // 422 (unprocessable): the dispatch payload carried an unresolvable issue key.
        return new IssueReferenceException(issueKey, repository, 422, false,
                "Issue \"" + issueKey + "\" could not be resolved in " + repository
                        + "; expected a numeric issue number or an existing issue URL.");
    }

    /** Transient fetch failure after the bounded retry budget was exhausted. */
    public static IssueReferenceException transientFailure(String issueKey, String repository,
                                                           String lastError) {
        return new IssueReferenceException(issueKey, repository, 502, true,
                "Failed to fetch issue \"" + issueKey + "\" in " + repository
                        + " after retries: " + lastError);
    }
}
