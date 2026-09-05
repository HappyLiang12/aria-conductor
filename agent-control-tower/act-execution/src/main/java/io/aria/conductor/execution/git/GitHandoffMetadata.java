package io.aria.conductor.execution.git;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write helpers for the SDD git-handoff metadata stored on a chain's
 * {@code templateParams} column (a JSON map of instantiation parameters).
 * The user-supplied parameters (e.g. {@code repoUrl}) travel with the chain,
 * and the system appends its own keys ({@code specCommitSha}) at spec-approval
 * time so the Dev-completion fallback can compare the branch HEAD against the
 * recorded spec commit.
 */
public final class GitHandoffMetadata {

    /** Instantiation parameter key carrying the target repository URL. */
    public static final String KEY_REPO_URL = "repoUrl";
    /** System key recording the branch HEAD sha right after the spec commit (Task 5). */
    public static final String KEY_SPEC_COMMIT_SHA = "specCommitSha";

    // ---- SDD spec-task issue grounding keys (R9-F2) -------------------------
    /** Instantiation parameter key carrying the issue reference ({@code #<number>}, number or URL). */
    public static final String KEY_ISSUE_REF = "issueRef";
    /** Instantiation parameter key carrying the authoritative {@code owner/repo} of the issue. */
    public static final String KEY_ISSUE_REPO = "issueRepo";
    /** System key recording the resolved numeric issue number (observability/audit). */
    public static final String KEY_ISSUE_NUMBER = "issueNumber";
    /** System key recording the sha-256 of the inlined issue title/body/labels (observability/audit). */
    public static final String KEY_ISSUE_BODY_SHA = "issueBodySha";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GitHandoffMetadata() {
    }

    /** Chain-scoped branch name: {@code sdd/<chainId>}. */
    public static String branchName(UUID chainId) {
        return "sdd/" + chainId;
    }

    /** Parse a {@code templateParams} JSON map; never throws (empty map on bad input). */
    public static Map<String, String> parse(String templateParams) {
        if (templateParams == null || templateParams.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(templateParams, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Serialize a param map to JSON; never throws ({@code "{}"} on failure). */
    public static String toJson(Map<String, String> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Return {@code templateParams} with the given entry upserted (preserves other entries). */
    public static String withEntry(String templateParams, String key, String value) {
        Map<String, String> map = parse(templateParams);
        map.put(key, value);
        return toJson(map);
    }
}
