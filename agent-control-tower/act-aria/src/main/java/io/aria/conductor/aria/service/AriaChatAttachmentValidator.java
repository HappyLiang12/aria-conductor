package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.execution.runtime.CoreAttachment;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * One validation pass over the chat's image attachments: allowlist, count,
 * decoded sizes and base64. The images are run context only; the summary it
 * returns is the only form that may be persisted.
 */
public final class AriaChatAttachmentValidator {

    public static final int MAX_ATTACHMENTS = 5;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");

    public record Rejection(HttpStatus status, String message) {}
    public record Validated(List<CoreAttachment> parts, String summary) {}
    public record Result(Rejection rejection, Validated validated) {}

    private AriaChatAttachmentValidator() {
    }

    public static Result validate(List<AriaChatRequest.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new Result(null, new Validated(List.of(), ""));
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            return reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    "At most " + MAX_ATTACHMENTS + " images per turn");
        }
        List<CoreAttachment> parts = new ArrayList<>();
        List<String> described = new ArrayList<>();
        long total = 0;
        for (AriaChatRequest.Attachment attachment : attachments) {
            if (attachment == null || attachment.getMimeType() == null
                    || !ALLOWED_MIME.contains(attachment.getMimeType())) {
                return reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Attachments must be image/jpeg, image/png or image/webp");
            }
            if (attachment.getDataBase64() == null) {
                return reject(HttpStatus.BAD_REQUEST, "attachment.dataBase64 is required");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(attachment.getDataBase64());
            } catch (IllegalArgumentException e) {
                return reject(HttpStatus.BAD_REQUEST, "attachment.dataBase64 is not valid base64");
            }
            if (bytes.length == 0) {
                return reject(HttpStatus.UNPROCESSABLE_ENTITY, "attachment image is empty");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Each image must be at most " + MAX_IMAGE_BYTES + " bytes");
            }
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES) {
                return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Attachments must total at most " + MAX_TOTAL_BYTES + " bytes");
            }
            String name = attachment.getFilename() == null || attachment.getFilename().isBlank()
                    ? "image" : attachment.getFilename();
            parts.add(new CoreAttachment(attachment.getMimeType(), bytes, name));
            described.add(name + " (" + attachment.getMimeType() + ", "
                    + Math.max(1, Math.round(bytes.length / 1024.0)) + " KB)");
        }
        return new Result(null, new Validated(List.copyOf(parts),
                "[attached images: " + String.join(", ", described) + "]"));
    }

    private static Result reject(HttpStatus status, String message) {
        return new Result(new Rejection(status, message), null);
    }
}
