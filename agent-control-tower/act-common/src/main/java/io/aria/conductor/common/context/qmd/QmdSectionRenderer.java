package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared rendering for the qmd memory and knowledge sections. */
public final class QmdSectionRenderer {

    private QmdSectionRenderer() {
    }

    public static Optional<ContextSection> render(String id, String title, QmdResult result, int maxChars) {
        if (result == null || result.hits().isEmpty()) {
            return Optional.empty();
        }
        int cap = maxChars <= 0 ? 4_000 : maxChars;
        StringBuilder content = new StringBuilder();
        List<String> sources = new ArrayList<>();
        Sensitivity sensitivity = Sensitivity.PUBLIC;
        boolean truncated = false;
        for (QmdHit hit : result.hits()) {
            String line = "- " + hit.title() + " [" + hit.file() + "]"
                    + (hit.snippet() == null || hit.snippet().isBlank() ? "" : ": " + hit.snippet());
            int extra = content.length() == 0 ? line.length() : line.length() + 1;
            if (content.length() + extra > cap) {
                truncated = true;
                break;
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(line);
            sources.add(hit.file());
            if (hit.sensitivity().ordinal() > sensitivity.ordinal()) {
                sensitivity = hit.sensitivity();
            }
        }
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ContextSection(id, title, content.toString(), sensitivity,
                List.copyOf(sources), truncated, null));
    }
}
