package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AriaChatAttachmentValidatorTest {

    private static final int KB = 1024;

    @Test
    void noAttachmentsYieldAnEmptyResult() {
        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(null);

        assertThat(result.rejection()).isNull();
        assertThat(result.validated().parts()).isEmpty();
        assertThat(result.validated().summary()).isEmpty();
    }

    @Test
    void twoImagesDecodeAndSummarizeWithoutBase64() {
        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(List.of(
                attachment("image/jpeg", "photo.jpg", bytes(2 * KB)),
                attachment("image/png", "shot.png", bytes(KB))));

        assertThat(result.rejection()).isNull();
        assertThat(result.validated().parts()).hasSize(2);
        assertThat(result.validated().parts().get(0).mimeType()).isEqualTo("image/jpeg");
        assertThat(result.validated().parts().get(0).bytes()).hasSize(2 * KB);
        assertThat(result.validated().summary())
                .isEqualTo("[attached images: photo.jpg (image/jpeg, 2 KB), shot.png (image/png, 1 KB)]");
        assertThat(result.validated().summary()).doesNotContain("AAAA");
    }

    @Test
    void anUnsupportedMimeIs415() {
        assertRejected(List.of(attachment("application/pdf", "doc.pdf", bytes(10))),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void invalidBase64Is400() {
        AriaChatRequest.Attachment broken = attachment("image/jpeg", "x.jpg", bytes(10));
        broken.setDataBase64("not base64 !!");

        assertRejected(List.of(broken), HttpStatus.BAD_REQUEST);
    }

    @Test
    void moreThanFiveIs422() {
        List<AriaChatRequest.Attachment> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(attachment("image/jpeg", "x" + i + ".jpg", bytes(10)));
        }

        assertRejected(many, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aZeroByteImageIs422() {
        assertRejected(List.of(attachment("image/jpeg", "empty.jpg", new byte[0])),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void anImageOverFourMebibytesIs413() {
        assertRejected(List.of(attachment("image/jpeg", "big.jpg", bytes(4 * 1024 * KB + 1))),
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aTotalOverTenMebibytesIs413() {
        assertRejected(List.of(
                        attachment("image/jpeg", "a.jpg", bytes(4 * 1024 * KB)),
                        attachment("image/jpeg", "b.jpg", bytes(4 * 1024 * KB)),
                        attachment("image/jpeg", "c.jpg", bytes(3 * 1024 * KB))),
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void anOversizedRequestBodyIsRefusedBeforeDecoding() {
        AriaChatRequest.Attachment broken = attachment("image/jpeg", "x.jpg", bytes(10));
        broken.setDataBase64("not base64 !!");

        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(
                List.of(broken), AriaChatAttachmentValidator.MAX_REQUEST_BYTES + 1);

        assertThat(result.rejection().status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aBodyAtTheCapIsStillValidatedNormally() {
        AriaChatRequest.Attachment broken = attachment("image/jpeg", "x.jpg", bytes(10));
        broken.setDataBase64("not base64 !!");

        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(
                List.of(broken), AriaChatAttachmentValidator.MAX_REQUEST_BYTES);

        assertThat(result.rejection().status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theDtoToStringNeverPrintsTheBase64Payload() {
        String base64 = Base64.getEncoder().encodeToString("secret-payload".getBytes());

        assertThat(attachment("image/png", "p.png", "secret-payload".getBytes()).toString())
                .doesNotContain(base64)
                .doesNotContain("secret-payload");
    }

    private static void assertRejected(List<AriaChatRequest.Attachment> attachments, HttpStatus status) {
        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(attachments);
        assertThat(result.rejection()).isNotNull();
        assertThat(result.rejection().status()).isEqualTo(status);
    }

    private static AriaChatRequest.Attachment attachment(String mime, String name, byte[] bytes) {
        return AriaChatRequest.Attachment.builder()
                .mimeType(mime)
                .filename(name)
                .dataBase64(Base64.getEncoder().encodeToString(bytes))
                .build();
    }

    private static byte[] bytes(int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (i % 251 + 1);
        }
        return out;
    }
}
