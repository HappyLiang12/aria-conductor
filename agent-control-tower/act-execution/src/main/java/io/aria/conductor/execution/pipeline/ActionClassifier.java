package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.RiskTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Rule-based action classifier — determines risk level and approval requirements.
 * Consults the tool's governance riskTier (authoritative) before falling back to ActionType heuristics.
 */
@Slf4j
@Component
public class ActionClassifier {

    private final ToolRiskResolver riskResolver;

    public ActionClassifier(ToolRiskResolver riskResolver) {
        this.riskResolver = riskResolver;
    }

    public ActionClassification classify(Action action) {
        // Governance riskTier is authoritative: PUSH/DESTRUCTIVE always require approval
        RiskTier riskTier = riskResolver.resolve(action.name());
        if (riskTier == RiskTier.PUSH || riskTier == RiskTier.DESTRUCTIVE) {
            log.debug("Action '{}' has riskTier={} — requires approval", action.name(), riskTier);
            return ActionClassification.highRisk(riskTier.name());
        }

        // Fall back to ActionType-based classification for READ/WRITE_LOCAL tools
        return switch (action.type()) {
            case READ -> {
                log.debug("Classified action '{}' as LOW risk READ", action.name());
                yield ActionClassification.lowRisk("READ");
            }
            case WRITE -> {
                log.debug("Classified action '{}' as MEDIUM risk WRITE", action.name());
                yield ActionClassification.mediumRisk("WRITE");
            }
            case EXECUTE -> {
                log.debug("Classified action '{}' as MEDIUM risk EXECUTE", action.name());
                yield ActionClassification.mediumRisk("EXECUTE");
            }
            case HIGH_RISK -> {
                log.debug("Classified action '{}' as HIGH risk — requires approval", action.name());
                yield ActionClassification.highRisk("HIGH_RISK");
            }
        };
    }
}