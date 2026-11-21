package io.aria.conductor.execution.runtime;

import java.util.Arrays;

/**
 * One binary part sent with a core prompt. The bytes live in memory for the
 * turn only; no platform table, trajectory row or log line receives them.
 */
public record CoreAttachment(String mimeType, byte[] bytes, String filename) {

    public CoreAttachment {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("attachment bytes are required");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        filename = filename == null || filename.isBlank() ? "attachment" : filename;
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
