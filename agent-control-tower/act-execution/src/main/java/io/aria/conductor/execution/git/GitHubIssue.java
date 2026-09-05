package io.aria.conductor.execution.git;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * An issue resolved from a GitHub repository for the SDD spec-task grounding path
 * (R9-F2). Carries the authoritative repository ({@code owner/repo}), the numeric
 * issue number, and the full issue payload ({@code title}/{@code body}/{@code labels})
 * that is inlined into the BA task message so a spec-writing sub-agent never has to
 * invoke {@code gh issue view} itself.
 *
 * @param ownerRepo canonical {@code owner/repo} the issue belongs to
 * @param number    numeric issue number (GitHub issue identifier)
 * @param title     issue title (never {@code null})
 * @param body      issue body text; empty when GitHub reports no body
 * @param labels    resolved label names (never {@code null})
 */
public record GitHubIssue(String ownerRepo, int number, String title, String body, List<String> labels) {

    /** Sha-256 hex digest over the inlined issue payload (title/labels/body) for the audit record. */
    public String bodySha256() {
        String canonical = number + "\n"
                + (title == null ? "" : title) + "\n"
                + String.join(",", labels == null ? List.of() : labels) + "\n"
                + (body == null ? "" : body);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
