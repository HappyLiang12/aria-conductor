package io.aria.conductor.agent.repository;

import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 fix round 1 (M1): the KPI sum, the run DTO total and the
 * {@code runsWithoutUsage} count must be derived from one rule, so a single row
 * can never be REPORTED with a zero total, counted in neither bucket, or
 * contribute a value the DTO does not show.
 *
 * <p>A parts-only report (a positive provider part next to a zero total) is the
 * shape the old aggregate admitted but added as {@code 0}, while the classifier
 * called it REPORTED. The fixture below pins every shape at once: a row's SQL
 * contribution is asserted equal to the total {@link RunService} serves for the
 * same row, and the count is asserted equal to the number of NOT_REPORTED rows.
 */
class RunUsageAggregateQueryTest extends DataJpaTestBase {

    @Autowired
    RunRepository runs;

    @Test
    void theKpiTheDtoTotalAndTheUnreportedCountAgreeOnEveryShape() {
        Run neverObserved = run(0L, null, null);
        Run measuredZero = run(0L, 0L, 0L);
        Run partsOnlyInput = run(0L, 100L, null);
        Run partsOnlyOutput = run(0L, null, 30L);
        Run nonZeroTotal = run(120L, 100L, 20L);
        Run providerTotalOnly = run(90L, null, null);
        runs.saveAll(List.of(neverObserved, measuredZero, partsOnlyInput, partsOnlyOutput,
                nonZeroTotal, providerTotalOnly));
        flushAndClear();

        Map<UUID, Run> rows = Map.of(
                neverObserved.getId(), load(neverObserved),
                measuredZero.getId(), load(measuredZero),
                partsOnlyInput.getId(), load(partsOnlyInput),
                partsOnlyOutput.getId(), load(partsOnlyOutput),
                nonZeroTotal.getId(), load(nonZeroTotal),
                providerTotalOnly.getId(), load(providerTotalOnly));

        // The classification and the DTO total for the same fixture rows.
        assertThat(stateOf(rows, neverObserved)).isEqualTo("NOT_REPORTED");
        assertThat(dtoTotalOf(rows, neverObserved)).isNull();
        assertThat(stateOf(rows, measuredZero)).isEqualTo("NOT_REPORTED");
        assertThat(dtoTotalOf(rows, measuredZero)).isNull();
        assertThat(stateOf(rows, partsOnlyInput)).isEqualTo("REPORTED");
        assertThat(dtoTotalOf(rows, partsOnlyInput)).as("M1: a parts-only report is not a zero total")
                .isEqualTo(100L);
        assertThat(stateOf(rows, partsOnlyOutput)).isEqualTo("REPORTED");
        assertThat(dtoTotalOf(rows, partsOnlyOutput)).isEqualTo(30L);
        assertThat(stateOf(rows, nonZeroTotal)).isEqualTo("REPORTED");
        assertThat(dtoTotalOf(rows, nonZeroTotal)).isEqualTo(120L);
        assertThat(stateOf(rows, providerTotalOnly)).isEqualTo("REPORTED");
        assertThat(dtoTotalOf(rows, providerTotalOnly)).isEqualTo(90L);

        // The KPI is exactly the sum of the totals the DTO serves for the same rows.
        long dtoSum = rows.values().stream()
                .map(RunService::observedTotalTokens)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        assertThat(dtoSum).as("fixture arithmetic").isEqualTo(340L);
        assertThat(runs.sumReportedUsage()).as("KPI total equals the DTO totals").isEqualTo(dtoSum);

        // Every row is either measured or counted, never both and never neither.
        long unreported = rows.values().stream()
                .filter(run -> "NOT_REPORTED".equals(RunService.usageStateOf(run)))
                .count();
        assertThat(unreported).as("fixture arithmetic").isEqualTo(2L);
        assertThat(runs.countWithoutReportedUsage()).as("count equals the NOT_REPORTED rows")
                .isEqualTo(unreported);
        assertThat(runs.countWithoutReportedUsage() + rows.size() - unreported)
                .as("the two aggregates partition every row")
                .isEqualTo(rows.size());
    }

    private Run run(long totalTokensUsed, Long inputTokens, Long outputTokens) {
        Run run = TestDataBuilder.aRun().withStatus(RunStatus.COMPLETED)
                .withTotalTokensUsed(totalTokensUsed).build();
        run.setInputTokens(inputTokens);
        run.setOutputTokens(outputTokens);
        return run;
    }

    private Run load(Run saved) {
        return runs.findById(saved.getId()).orElseThrow();
    }

    private static String stateOf(Map<UUID, Run> rows, Run saved) {
        return RunService.usageStateOf(rows.get(saved.getId()));
    }

    private static Long dtoTotalOf(Map<UUID, Run> rows, Run saved) {
        return RunService.observedTotalTokens(rows.get(saved.getId()));
    }
}
