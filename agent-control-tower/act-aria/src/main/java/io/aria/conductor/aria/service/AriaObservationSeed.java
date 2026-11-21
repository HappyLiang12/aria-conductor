package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The text a glasses observation becomes on the run's prompt seed. It is the
 * only form of the observation that is persisted, so it carries metadata and
 * never image data.
 */
public final class AriaObservationSeed {

    static final int MAX_SEED_CHARS = 8192;

    private AriaObservationSeed() {
    }

    public static String build(AriaObserveRequest request) {
        AriaObserveRequest.Observation observation = request.getObservation();
        AriaObserveRequest.ClientCapabilities capabilities = request.getClientCapabilities();
        StringBuilder seed = new StringBuilder();
        seed.append("[glasses observation] note: ")
                .append(clip(request.getNote(), 500, "(none)")).append('\n');
        seed.append("capabilities: ar=").append(flag(capabilities == null ? null : capabilities.getAr()))
                .append(",vision=").append(flag(capabilities == null ? null : capabilities.getVision()))
                .append(",audio=").append(flag(capabilities == null ? null : capabilities.getAudio()))
                .append('\n');
        seed.append("captured_at: ")
                .append(observation.getTimestamp() == null
                        ? "unknown" : Instant.ofEpochMilli(observation.getTimestamp()))
                .append('\n');
        if (observation.getHeading() != null || observation.getPitch() != null
                || observation.getRoll() != null) {
            seed.append("orientation: heading=").append(num(observation.getHeading()))
                    .append(" pitch=").append(num(observation.getPitch()))
                    .append(" roll=").append(num(observation.getRoll())).append('\n');
        }
        seed.append("objects:\n");
        List<AriaObserveRequest.SceneObject> objects = observation.getObjects();
        if (objects == null || objects.isEmpty()) {
            seed.append("- (none)\n");
        } else {
            for (AriaObserveRequest.SceneObject object : objects) {
                if (object != null) {
                    seed.append(objectLine(object)).append('\n');
                }
            }
        }
        if (isNotBlank(observation.getOcrText())) {
            seed.append("ocr: \"").append(clip(observation.getOcrText(), 2000, "")).append("\"\n");
        }
        if (isNotBlank(observation.getLocalSummary())) {
            seed.append("summary: \"")
                    .append(clip(observation.getLocalSummary(), 500, "")).append("\"\n");
        }
        return capSeed(seed.toString());
    }

    private static String capSeed(String seed) {
        if (seed.length() <= MAX_SEED_CHARS) {
            return seed;
        }
        String suffix = " [truncated]";
        return seed.substring(0, MAX_SEED_CHARS - suffix.length()) + suffix;
    }

    private static String objectLine(AriaObserveRequest.SceneObject object) {
        StringBuilder line = new StringBuilder("-");
        if (object.getTrackId() != null) {
            line.append(" trackId=").append(object.getTrackId());
        }
        if (isNotBlank(object.getId())) {
            line.append(" objectId=").append(object.getId());
        }
        if (isNotBlank(object.getLabel())) {
            line.append(" label=\"").append(clip(object.getLabel(), 64, "")).append('"');
        }
        if (object.getConfidence() != null) {
            line.append(" confidence=")
                    .append(String.format(Locale.ROOT, "%.2f", object.getConfidence()));
        }
        if (object.getBbox() != null && object.getBbox().size() == 4) {
            line.append(" bbox=(").append(vector(object.getBbox(), 4)).append(')');
        }
        if (object.getCenter() != null && object.getCenter().size() == 2) {
            line.append(" center=(").append(vector(object.getCenter(), 2)).append(')');
        }
        if (isNotBlank(object.getDescription())) {
            line.append(" description=\"")
                    .append(clip(object.getDescription(), 200, "")).append('"');
        }
        return line.toString();
    }

    private static String vector(List<Double> values, int expected) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < expected; i++) {
            if (i > 0) {
                out.append(',');
            }
            Double value = i < values.size() ? values.get(i) : null;
            out.append(value == null ? "?" : String.format(Locale.ROOT, "%.3f", value));
        }
        return out.toString();
    }

    private static String flag(Boolean value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    private static String num(Double value) {
        return value == null ? "unknown" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String clip(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= max ? value : value.substring(0, max) + " [truncated]";
    }
}
