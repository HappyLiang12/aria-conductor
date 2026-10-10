package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationQuestionsTest {

    @Test
    void markerOnTheLastLineIsTheQuestion() {
        String output = "Working on it.\nDone so far:\n- step 1\n[NEED-INPUT] Which database should I use?";
        assertThat(ClarificationQuestions.awaitingInput(output))
                .isEqualTo("Which database should I use?");
    }

    @Test
    void bareMarkerFallsBackToTheWholeOutput() {
        assertThat(ClarificationQuestions.awaitingInput("before\n[NEED-INPUT]"))
                .isEqualTo("before");
    }

    @Test
    void trailingQuestionMarkOnTheLastLineIsTheQuestion() {
        assertThat(ClarificationQuestions.awaitingInput("Progress notes\nShould I pin the model to efficient?"))
                .isEqualTo("Should I pin the model to efficient?");
    }

    @Test
    void fullwidthQuestionMarkIsDetected() {
        assertThat(ClarificationQuestions.awaitingInput("準備好了\n要用哪個 model？"))
                .isEqualTo("要用哪個 model？");
    }

    @Test
    void plainCompletionIsNotAQuestion() {
        assertThat(ClarificationQuestions.awaitingInput("All done. Tests pass. See report.md")).isNull();
        assertThat(ClarificationQuestions.awaitingInput("Finished. The historical name was \"?\"")).isNull();
    }

    @Test
    void questionMarkInTheMiddleDoesNotCount() {
        assertThat(ClarificationQuestions.awaitingInput("Asked myself why? Then solved it.")).isNull();
    }

    @Test
    void blankAndNullAreNeverQuestions() {
        assertThat(ClarificationQuestions.awaitingInput(null)).isNull();
        assertThat(ClarificationQuestions.awaitingInput("   \n  ")).isNull();
    }
}
