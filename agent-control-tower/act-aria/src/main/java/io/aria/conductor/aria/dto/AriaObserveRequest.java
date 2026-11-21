package io.aria.conductor.aria.dto;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * One glasses observation. The image is never part of the run's persisted
 * prompt; its base64 must stay out of every toString() and log line, which is
 * why the field carries {@link ToString.Exclude}.
 */
@Data
public class AriaObserveRequest {

    private String conversationId;
    private String note;
    private Image image;
    private ClientCapabilities clientCapabilities;
    private Observation observation;

    @Data
    public static class Image {
        private String mimeType;
        @ToString.Exclude
        private String dataBase64;
        private Integer width;
        private Integer height;
    }

    @Data
    public static class ClientCapabilities {
        private String kind;
        private Boolean ar;
        private Boolean vision;
        private Boolean audio;
    }

    @Data
    public static class Observation {
        private Long timestamp;
        private Double heading;
        private Double pitch;
        private Double roll;
        private List<SceneObject> objects;
        private String ocrText;
        private String localSummary;
    }

    @Data
    public static class SceneObject {
        private String id;
        private Integer trackId;
        private String label;
        private Double confidence;
        private List<Double> bbox;
        private List<Double> center;
        private String description;
    }
}
