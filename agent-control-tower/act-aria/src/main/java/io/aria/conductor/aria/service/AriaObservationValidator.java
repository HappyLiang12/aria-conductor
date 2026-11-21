package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;
import org.springframework.http.HttpStatus;

import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * The observe request's one validation pass: MIME, base64, size and geometry.
 * Every rejected request is answered before a Run exists, so a bad frame can
 * never consume an admission slot.
 */
public final class AriaObservationValidator {

    public static final int MAX_DECODED_BYTES = 1_572_864;
    public static final long MAX_CONTENT_LENGTH = 3_000_000L;
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png");
    private static final int MAX_OBJECTS = 32;

    public record Rejection(HttpStatus status, String message) {}
    public record ObservedImage(byte[] bytes, String mimeType) {}

    public record Result(Rejection rejection, ObservedImage image) {
        public boolean isRejected() {
            return rejection != null;
        }
    }

    private AriaObservationValidator() {
    }

    public static Result validate(AriaObserveRequest request, long contentLength) {
        if (contentLength > MAX_CONTENT_LENGTH) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Request body exceeds " + MAX_CONTENT_LENGTH + " bytes");
        }
        if (request == null || request.getImage() == null
                || request.getImage().getDataBase64() == null
                || request.getImage().getDataBase64().isBlank()) {
            return reject(HttpStatus.BAD_REQUEST, "image.dataBase64 is required");
        }
        String mime = request.getImage().getMimeType();
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            return reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "image.mimeType must be image/jpeg or image/png");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.getImage().getDataBase64());
        } catch (IllegalArgumentException e) {
            return reject(HttpStatus.BAD_REQUEST, "image.dataBase64 is not valid base64");
        }
        if (bytes.length == 0) {
            return reject(HttpStatus.BAD_REQUEST, "image is empty");
        }
        if (bytes.length > MAX_DECODED_BYTES) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Decoded image exceeds " + MAX_DECODED_BYTES + " bytes");
        }
        if (request.getObservation() == null) {
            return reject(HttpStatus.BAD_REQUEST, "observation is required");
        }
        List<AriaObserveRequest.SceneObject> objects = request.getObservation().getObjects();
        if (objects != null) {
            if (objects.size() > MAX_OBJECTS) {
                return reject(HttpStatus.UNPROCESSABLE_ENTITY,
                        "observation.objects must not exceed " + MAX_OBJECTS);
            }
            for (AriaObserveRequest.SceneObject object : objects) {
                if (object == null) {
                    continue;
                }
                Rejection geometry = validateGeometry(object);
                if (geometry != null) {
                    return new Result(geometry, null);
                }
            }
        }
        return new Result(null, new ObservedImage(bytes, mime));
    }

    private static Rejection validateGeometry(AriaObserveRequest.SceneObject object) {
        if (object.getBbox() != null) {
            List<Double> bbox = object.getBbox();
            if (bbox.size() != 4 || !inUnitRange(bbox.get(0)) || !inUnitRange(bbox.get(1))
                    || !inUnitRange(bbox.get(2)) || !inUnitRange(bbox.get(3))
                    || bbox.get(2) <= 0 || bbox.get(3) <= 0) {
                return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY,
                        "object " + object.getId() + " has an invalid bbox");
            }
        }
        if (object.getCenter() != null) {
            List<Double> center = object.getCenter();
            if (center.size() != 2 || !inUnitRange(center.get(0)) || !inUnitRange(center.get(1))) {
                return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY,
                        "object " + object.getId() + " has an invalid center");
            }
        }
        if (object.getConfidence() != null && !inUnitRange(object.getConfidence())) {
            return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY,
                    "object " + object.getId() + " has a confidence outside 0..1");
        }
        return null;
    }

    private static boolean inUnitRange(Double value) {
        return value != null && value >= 0.0 && value <= 1.0;
    }

    private static Result reject(HttpStatus status, String message) {
        return new Result(new Rejection(status, message), null);
    }
}
