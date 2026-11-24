package io.aria.conductor.common.context;

import java.util.ArrayList;
import java.util.List;

/** The query text shared by the qmd memory and knowledge contributors. */
public final class ContextQueryText {

    private ContextQueryText() {
    }

    public static String of(ContextRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(request.goal() == null ? "" : request.goal());
        List<String> userTurns = new ArrayList<>();
        for (ContextMessage message : request.priorHistory()) {
            if (message != null && "user".equalsIgnoreCase(message.role())) {
                userTurns.add(message.content());
            }
        }
        for (String turn : userTurns.subList(Math.max(0, userTurns.size() - 3), userTurns.size())) {
            text.append('\n').append(turn);
        }
        return text.toString().trim();
    }
}
