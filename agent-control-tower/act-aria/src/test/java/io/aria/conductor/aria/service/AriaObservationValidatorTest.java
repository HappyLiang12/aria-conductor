package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AriaObservationValidatorTest {

    private static final String JPEG_BASE64 = "/9j/2Q==";

    @Test
    void aValidObservationIsDecoded() {
        AriaObservationValidator.Result result =
                AriaObservationValidator.validate(request(objectWithBbox(0.1, 0.2, 0.3, 0.4)), 1_000);

        assertThat(result.isRejected()).isFalse();
        assertThat(result.image().mimeType()).isEqualTo("image/jpeg");
        assertThat(result.image().bytes()).isNotEmpty();
    }

    @Test
    void anUnknownMimeIs415() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setMimeType("image/gif");

        assertRejected(request, 1_000, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void invalidBase64Is400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setDataBase64("not base64 !!");

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOversizedContentLengthIs413() {
        assertRejected(request(objectWithBbox(0.1, 0.2, 0.3, 0.4)), 3_000_001,
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void moreThanThirtyTwoObjectsIs422() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        List<AriaObserveRequest.SceneObject> objects = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            objects.add(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        }
        request.getObservation().setObjects(objects);

        assertRejected(request, 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aBboxOutsideTheUnitSquareIs422() {
        assertRejected(request(objectWithBbox(-0.1, 0.2, 0.3, 0.4)), 1_000,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aZeroWidthBboxIs422() {
        assertRejected(request(objectWithBbox(0.1, 0.2, 0.0, 0.4)), 1_000,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aMissingImageIs400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.setImage(null);

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNullRequestIs400() {
        assertRejected(null, 1_000, HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBlankBase64Is400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setDataBase64("   ");

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingMimeIs415() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setMimeType(null);

        assertRejected(request, 1_000, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void aPngImageIsAccepted() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setMimeType("image/png");

        AriaObservationValidator.Result result = AriaObservationValidator.validate(request, 1_000);

        assertThat(result.isRejected()).isFalse();
        assertThat(result.image().mimeType()).isEqualTo("image/png");
    }

    @Test
    void aDecodedImageOverTheCapIs413() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        byte[] tooBig = new byte[AriaObservationValidator.MAX_DECODED_BYTES + 1];
        request.getImage().setDataBase64(Base64.getEncoder().encodeToString(tooBig));

        assertRejected(request, 1_000, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aMissingObservationIs400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.setObservation(null);

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    @Test
    void anAbsentObjectListIsAccepted() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getObservation().setObjects(null);

        AriaObservationValidator.Result result = AriaObservationValidator.validate(request, 1_000);

        assertThat(result.isRejected()).isFalse();
    }

    @Test
    void aNullObjectIsSkipped() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        List<AriaObserveRequest.SceneObject> objects = new ArrayList<>();
        objects.add(null);
        objects.add(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getObservation().setObjects(objects);

        AriaObservationValidator.Result result = AriaObservationValidator.validate(request, 1_000);

        assertThat(result.isRejected()).isFalse();
    }

    @Test
    void aBboxOfTheWrongLengthIs422() {
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setBbox(List.of(0.1, 0.2, 0.3));

        assertRejected(request(object), 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aZeroHeightBboxIs422() {
        assertRejected(request(objectWithBbox(0.1, 0.2, 0.3, 0.0)), 1_000,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aBboxWithANullComponentIs422() {
        List<Double> bbox = new ArrayList<>();
        bbox.add(0.1);
        bbox.add(0.2);
        bbox.add(null);
        bbox.add(0.4);
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setBbox(bbox);

        assertRejected(request(object), 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void anAbsentCenterIsAllowed() {
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setCenter(null);

        AriaObservationValidator.Result result = AriaObservationValidator.validate(request(object), 1_000);

        assertThat(result.isRejected()).isFalse();
    }

    @Test
    void aCenterOfTheWrongLengthIs422() {
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setCenter(List.of(0.5));

        assertRejected(request(object), 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aCenterOutsideTheUnitSquareIs422() {
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setCenter(List.of(1.5, 0.5));

        assertRejected(request(object), 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void anAbsentConfidenceIsAllowed() {
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setConfidence(null);

        AriaObservationValidator.Result result = AriaObservationValidator.validate(request(object), 1_000);

        assertThat(result.isRejected()).isFalse();
    }

    @Test
    void aConfidenceOutsideTheUnitSquareIs422() {
        AriaObserveRequest.SceneObject object = objectWithBbox(0.1, 0.2, 0.3, 0.4);
        object.setConfidence(1.4);

        assertRejected(request(object), 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aConversationIdLongerThanTheColumnIs400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.setConversationId("observe-e2e-" + java.util.UUID.randomUUID());

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    private static void assertRejected(AriaObserveRequest request, long contentLength, HttpStatus status) {
        AriaObservationValidator.Result result = AriaObservationValidator.validate(request, contentLength);
        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection().status()).isEqualTo(status);
    }

    static AriaObserveRequest request(AriaObserveRequest.SceneObject object) {
        AriaObserveRequest.Image image = new AriaObserveRequest.Image();
        image.setMimeType("image/jpeg");
        image.setDataBase64(JPEG_BASE64);
        image.setWidth(640);
        image.setHeight(480);

        AriaObserveRequest.ClientCapabilities capabilities = new AriaObserveRequest.ClientCapabilities();
        capabilities.setKind("glasses");
        capabilities.setAr(true);
        capabilities.setVision(true);

        AriaObserveRequest.Observation observation = new AriaObserveRequest.Observation();
        observation.setTimestamp(1760000000000L);
        observation.setHeading(123.4);
        observation.setPitch(-2.1);
        observation.setRoll(0.3);
        observation.setObjects(new ArrayList<>(List.of(object)));
        observation.setOcrText("SONY");
        observation.setLocalSummary("1 remote");

        AriaObserveRequest request = new AriaObserveRequest();
        request.setConversationId("conv-1");
        request.setNote("AR mode observation");
        request.setImage(image);
        request.setClientCapabilities(capabilities);
        request.setObservation(observation);
        return request;
    }

    static AriaObserveRequest.SceneObject objectWithBbox(double x, double y, double w, double h) {
        AriaObserveRequest.SceneObject object = new AriaObserveRequest.SceneObject();
        object.setId("o1");
        object.setTrackId(7);
        object.setLabel("remote");
        object.setConfidence(0.91);
        object.setBbox(List.of(x, y, w, h));
        object.setCenter(List.of(x + w / 2, y + h / 2));
        object.setDescription("black TV remote on desk");
        return object;
    }
}
