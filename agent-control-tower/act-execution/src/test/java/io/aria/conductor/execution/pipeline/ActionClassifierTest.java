package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.RiskTier;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Decision-table coverage for {@link ActionClassifier}.
 *
 * <p>The classifier has a two-layer contract:
 * <ol>
 *   <li>The governance {@link RiskTier} is authoritative — a PUSH or DESTRUCTIVE
 *       tool always yields a HIGH-risk classification that requires approval,
 *       regardless of the {@link ActionType} heuristic.</li>
 *   <li>For READ / WRITE_LOCAL (or unknown) tiers it falls back to the
 *       {@link ActionType} heuristic: READ→LOW, WRITE/EXECUTE→MEDIUM, HIGH_RISK→HIGH.</li>
 * </ol>
 * These tests pin down every cell of that table so mutations of the tier gate,
 * the risk-level strings, the category strings, or the approval flag are killed.
 */
@ExtendWith(MockitoExtension.class)
class ActionClassifierTest {

    @Mock
    private ToolRiskResolver riskResolver;

    private Action action(String name, ActionType type) {
        return new Action(name, type, "{}", "tc-1");
    }

    // --- Layer 1: governance tier is authoritative (overrides ActionType) ---

    @ParameterizedTest(name = "riskTier {0} forces HIGH+approval regardless of ActionType {1}")
    @CsvSource({
            "PUSH,READ",
            "PUSH,WRITE",
            "PUSH,EXECUTE",
            "PUSH,HIGH_RISK",
            "DESTRUCTIVE,READ",
            "DESTRUCTIVE,WRITE",
            "DESTRUCTIVE,EXECUTE",
            "DESTRUCTIVE,HIGH_RISK"
    })
    void classify_returnsHighRiskAndCategoryFromTier_whenTierIsPushOrDestructive(RiskTier tier, ActionType type) {
        when(riskResolver.resolve("git_push")).thenReturn(tier);
        ActionClassifier classifier = new ActionClassifier(riskResolver);

        ActionClassification result = classifier.classify(action("git_push", type));

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.requiresApproval()).isTrue();
        // Category must be the *tier* name, not the ActionType — proves the tier branch wins.
        assertThat(result.category()).isEqualTo(tier.name());
    }

    // --- Layer 2: ActionType heuristic for non-escalating tiers ---

    @ParameterizedTest(name = "tier {0} + READ action -> LOW/READ/no-approval")
    @EnumSource(value = RiskTier.class, names = {"READ", "WRITE_LOCAL"})
    void classify_readActionIsLowRisk_whenTierDoesNotEscalate(RiskTier tier) {
        when(riskResolver.resolve("read_file")).thenReturn(tier);
        ActionClassifier classifier = new ActionClassifier(riskResolver);

        ActionClassification result = classifier.classify(action("read_file", ActionType.READ));

        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.requiresApproval()).isFalse();
        assertThat(result.category()).isEqualTo("READ");
    }

    @ParameterizedTest(name = "ActionType {0} -> MEDIUM/{0}/no-approval for READ tier")
    @EnumSource(value = ActionType.class, names = {"WRITE", "EXECUTE"})
    void classify_writeOrExecuteIsMediumRiskWithoutApproval_whenTierIsRead(ActionType type) {
        when(riskResolver.resolve("do_thing")).thenReturn(RiskTier.READ);
        ActionClassifier classifier = new ActionClassifier(riskResolver);

        ActionClassification result = classifier.classify(action("do_thing", type));

        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.requiresApproval()).isFalse();
        assertThat(result.category()).isEqualTo(type.name());
    }

    @org.junit.jupiter.api.Test
    void classify_highRiskActionTypeRequiresApproval_evenWhenTierIsRead() {
        when(riskResolver.resolve("danger")).thenReturn(RiskTier.READ);
        ActionClassifier classifier = new ActionClassifier(riskResolver);

        ActionClassification result = classifier.classify(action("danger", ActionType.HIGH_RISK));

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.category()).isEqualTo("HIGH_RISK");
    }

    @org.junit.jupiter.api.Test
    void classify_writeLocalTierWithWriteAction_staysMediumNotEscalated() {
        // WRITE_LOCAL must NOT escalate to approval — only PUSH/DESTRUCTIVE do.
        when(riskResolver.resolve("git_commit")).thenReturn(RiskTier.WRITE_LOCAL);
        ActionClassifier classifier = new ActionClassifier(riskResolver);

        ActionClassification result = classifier.classify(action("git_commit", ActionType.WRITE));

        assertThat(result.requiresApproval()).isFalse();
        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
    }

    @org.junit.jupiter.api.Test
    void classify_delegatesToResolverUsingActionName() {
        lenient().when(riskResolver.resolve("read_file")).thenReturn(RiskTier.READ);
        ActionClassifier classifier = new ActionClassifier(riskResolver);

        classifier.classify(action("read_file", ActionType.READ));

        org.mockito.Mockito.verify(riskResolver).resolve("read_file");
    }
}
