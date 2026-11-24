package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.model.Sensitivity;

public record QmdHit(String title, String file, String snippet, double score, Sensitivity sensitivity) {
}
