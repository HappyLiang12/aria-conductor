package io.aria.conductor.aria.intent;

import org.springframework.stereotype.Component;

@Component
public class IntentClassifier {

    public String classify(String message) {
        String lower = message.toLowerCase();
        if (matches(lower, "agent", "status", "list", "show agents", "how many agents"))
            return "agent.status";
        if (matches(lower, "start", "run", "execute", "begin"))
            return "run.start";
        if (matches(lower, "approve", "deny", "approval", "pending"))
            return "approval.status";
        if (matches(lower, "knowledge", "skill", "script", "what do we know"))
            return "knowledge.query";
        if (matches(lower, "dashboard", "summary", "overview", "stats"))
            return "dashboard.summary";
        return "general";
    }

    private boolean matches(String lower, String... keywords) {
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
