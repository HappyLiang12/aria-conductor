package io.aria.conductor.execution.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Rule-based action classifier — determines risk level and approval requirements.
 */
@Slf4j
@Component
public class ActionClassifier {

    public ActionClassification classify(Action action) {
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