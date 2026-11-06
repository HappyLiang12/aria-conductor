package io.aria.conductor.execution.runtime;

/**
 * Detects a turn that ended with a clarification question (2026-10-05 spec §3).
 * Primary signal: the last non-empty line starts with the [NEED-INPUT] marker
 * the prompt seed instructs the core to use. Fallback: the last non-empty line
 * ends with a question mark (ASCII or fullwidth). A miss degrades to today's
 * behavior — the run completes and the review card carries the outcome; a
 * false positive only parks the run with the question visible, and the
 * operator finalizes manually.
 */
public final class ClarificationQuestions {

    public static final String MARKER = "[NEED-INPUT]";

    private ClarificationQuestions() {
    }

    public static String awaitingInput(String finalOutput) {
        if (finalOutput == null || finalOutput.isBlank()) {
            return null;
        }
        String trimmed = finalOutput.strip();
        int newline = trimmed.lastIndexOf('\n');
        String last = (newline >= 0 ? trimmed.substring(newline + 1) : trimmed).trim();
        if (last.startsWith(MARKER)) {
            String question = last.substring(MARKER.length()).trim();
            if (!question.isEmpty()) {
                return question;
            }
            // Bare marker with no question text: fall back to the whole output
            // minus the marker line itself; only a lone bare marker keeps the
            // whole stripped output.
            String body = (newline >= 0 ? trimmed.substring(0, newline) : "").trim();
            return body.isEmpty() ? trimmed : body;
        }
        return (last.endsWith("?") || last.endsWith("？")) ? last : null;
    }
}
