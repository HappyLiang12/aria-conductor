package io.aria.conductor.aria.live;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure half of spec L5 / X10: a phone frame is JPEG, decodes, and its base64 is at
 * most 256 KiB. The validator never throws and never touches a socket; the handler maps
 * its two failures to {@code frame_too_large} / {@code frame_invalid}.
 */
class LiveFrameValidatorTest {

    private static final String JPEG = "image/jpeg";

    @Test
    void aJpegWithinTheLimitIsAcceptedAndDecoded() {
        byte[] jpeg = "not-really-a-jpeg-but-bytes".getBytes(StandardCharsets.UTF_8);

        LiveFrameValidator.Result result = LiveFrameValidator.validate(JPEG, Base64.getEncoder().encodeToString(jpeg));

        assertThat(result.accepted()).isTrue();
        assertThat(result.failure()).isNull();
        assertThat(result.jpeg()).isEqualTo(jpeg);
    }

    @Test
    void aNonJpegMimeIsInvalid() {
        String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});

        assertThat(LiveFrameValidator.validate("image/png", base64).failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        assertThat(LiveFrameValidator.validate("IMAGE/JPEG", base64).failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        assertThat(LiveFrameValidator.validate("image/jpeg; charset=utf-8", base64).failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        assertThat(LiveFrameValidator.validate(null, base64).failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        assertThat(LiveFrameValidator.validate(" ", base64).failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
    }

    @Test
    void base64ThatDoesNotDecodeIsInvalid() {
        assertThat(LiveFrameValidator.validate(JPEG, "not base64 at all").failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        assertThat(LiveFrameValidator.validate(JPEG, "").failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        assertThat(LiveFrameValidator.validate(JPEG, null).failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
        // A data URL prefix is not the contract's raw base64.
        assertThat(LiveFrameValidator.validate(JPEG, "data:image/jpeg;base64,AAAA").failure())
                .isEqualTo(LiveFrameValidator.Failure.INVALID);
    }

    @Test
    void theBase64LimitIsExactly256KiBOfCharacters() {
        String full = "A".repeat(LiveFrameValidator.MAX_BASE64_LENGTH);
        String over = "A".repeat(LiveFrameValidator.MAX_BASE64_LENGTH + 1);

        assertThat(LiveFrameValidator.validate(JPEG, full).accepted()).isTrue();
        assertThat(LiveFrameValidator.validate(JPEG, over).failure())
                .isEqualTo(LiveFrameValidator.Failure.TOO_LARGE);
    }

    @Test
    void anOversizedFrameIsTooLargeBeforeTheMimeIsEvenLookedAt() {
        String over = "A".repeat(LiveFrameValidator.MAX_BASE64_LENGTH + 1);

        assertThat(LiveFrameValidator.validate("image/png", over).failure())
                .isEqualTo(LiveFrameValidator.Failure.TOO_LARGE);
    }
}
