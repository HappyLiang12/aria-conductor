package io.aria.conductor.aria.live;

import java.util.Base64;

/**
 * The pure half of the image-frame rule (spec L5 / X10): JPEG only, base64 that decodes,
 * at most 256 KiB of base64 characters. It reads no socket and never throws; the handler
 * turns {@link Failure#TOO_LARGE} into {@code frame_too_large} and anything else into
 * {@code frame_invalid}, then keeps the socket open.
 *
 * <p>The size test comes first: an oversized frame is refused without decoding a byte,
 * and a frame that is both oversized and wrongly typed is reported as oversized (the
 * actionable fact — the phone must shrink it — not the mime).
 */
public final class LiveFrameValidator {

    /** 256 KiB of base64 characters: the phone's encoder emits 4 chars per 3 bytes. */
    public static final int MAX_BASE64_LENGTH = 262_144;
    static final String JPEG = "image/jpeg";

    private LiveFrameValidator() {
    }

    public static Result validate(String mime, String dataBase64) {
        if (dataBase64 == null) {
            return Result.rejected(Failure.INVALID);
        }
        if (dataBase64.length() > MAX_BASE64_LENGTH) {
            return Result.rejected(Failure.TOO_LARGE);
        }
        if (!JPEG.equals(mime) || dataBase64.isEmpty()) {
            return Result.rejected(Failure.INVALID);
        }
        try {
            return Result.ok(Base64.getDecoder().decode(dataBase64));
        } catch (IllegalArgumentException e) {
            return Result.rejected(Failure.INVALID);
        }
    }

    public enum Failure {
        /** More than {@link #MAX_BASE64_LENGTH} base64 characters. */
        TOO_LARGE,
        /** Not a decodable, non-empty base64 payload with mime {@code image/jpeg}. */
        INVALID
    }

    /** @param failure null when accepted, else why it was refused */
    public record Result(byte[] jpeg, Failure failure) {

        static Result ok(byte[] jpeg) {
            return new Result(jpeg, null);
        }

        static Result rejected(Failure failure) {
            return new Result(null, failure);
        }

        public boolean accepted() {
            return failure == null;
        }
    }
}
