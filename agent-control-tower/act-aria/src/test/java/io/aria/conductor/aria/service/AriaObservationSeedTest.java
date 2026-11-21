package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AriaObservationSeedTest {

    @Test
    void theSeedIsTextOnlyAndNeverCarriesTheImage() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.42, 0.55, 0.12, 0.08));

        String seed = AriaObservationSeed.build(request);

        assertThat(seed).startsWith("[glasses observation] note: AR mode observation");
        assertThat(seed).contains("capabilities: ar=true,vision=true,audio=false");
        assertThat(seed).contains("captured_at: 2025-10-09T08:53:20Z");
        assertThat(seed).contains("trackId=7 objectId=o1 label=\"remote\"");
        assertThat(seed).contains("bbox=(0.420,0.550,0.120,0.080)");
        assertThat(seed).doesNotContain(request.getImage().getDataBase64());
    }

    @Test
    void theSeedIsCappedAtEightKilobytes() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        List<AriaObserveRequest.SceneObject> objects = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            AriaObserveRequest.SceneObject object =
                    AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1);
            object.setDescription("d".repeat(250));
            objects.add(object);
        }
        request.getObservation().setObjects(objects);

        String seed = AriaObservationSeed.build(request);

        assertThat(seed.length()).isLessThanOrEqualTo(8192);
        assertThat(seed).endsWith(" [truncated]");
    }

    @Test
    void theRequestToStringNeverPrintsTheImage() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));

        assertThat(request.toString()).doesNotContain(request.getImage().getDataBase64());
    }

    @Test
    void aSparseObservationStillRendersAUsableSeed() {
        AriaObserveRequest request = requestWithoutObjects();
        request.setNote(null);
        request.setClientCapabilities(null);
        request.getObservation().setTimestamp(null);
        request.getObservation().setHeading(null);
        request.getObservation().setPitch(null);
        request.getObservation().setRoll(null);
        request.getObservation().setObjects(null);
        request.getObservation().setOcrText(null);
        request.getObservation().setLocalSummary(null);

        String seed = AriaObservationSeed.build(request);

        assertThat(seed).startsWith("[glasses observation] note: (none)");
        assertThat(seed).contains("capabilities: ar=false,vision=false,audio=false");
        assertThat(seed).contains("captured_at: unknown");
        assertThat(seed).doesNotContain("orientation:");
        assertThat(seed).contains("objects:\n- (none)");
        assertThat(seed).doesNotContain("ocr:");
        assertThat(seed).doesNotContain("summary:");
    }

    @Test
    void anEmptyObjectListRendersNone() {
        AriaObserveRequest request = requestWithoutObjects();
        request.getObservation().setObjects(new ArrayList<>());

        assertThat(AriaObservationSeed.build(request)).contains("objects:\n- (none)");
    }

    @Test
    void aNullObjectEntryIsSkipped() {
        AriaObserveRequest request = requestWithoutObjects();
        List<AriaObserveRequest.SceneObject> objects = new ArrayList<>();
        objects.add(null);
        objects.add(AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        request.getObservation().setObjects(objects);

        String seed = AriaObservationSeed.build(request);

        assertThat(seed).contains("bbox=(0.400,0.500,0.100,0.100)");
        assertThat(seed.lines().filter(line -> line.equals("-")).count()).isZero();
    }

    @Test
    void anObjectWithNoFieldsRendersABareDash() {
        AriaObserveRequest request = requestWithoutObjects();
        request.getObservation().setObjects(new ArrayList<>(List.of(new AriaObserveRequest.SceneObject())));

        assertThat(AriaObservationSeed.build(request)).contains("\n-\n");
    }

    @Test
    void longFreeTextIsClippedWithItsOwnSuffix() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        request.setNote("n".repeat(600));
        AriaObserveRequest.SceneObject object = request.getObservation().getObjects().get(0);
        object.setLabel("l".repeat(100));

        String seed = AriaObservationSeed.build(request);

        assertThat(seed).contains("note: " + "n".repeat(500) + " [truncated]\n");
        assertThat(seed).contains("label=\"" + "l".repeat(64) + " [truncated]\"");
    }

    @Test
    void orientationKeepsUnknownForMissingAxes() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        request.getObservation().setHeading(null);
        request.getObservation().setPitch(null);

        assertThat(AriaObservationSeed.build(request))
                .contains("orientation: heading=unknown pitch=unknown roll=0.3");
    }

    @Test
    void malformedVectorsAreDroppedAndNullComponentsRenderAsUnknown() {
        AriaObserveRequest request = requestWithoutObjects();
        AriaObserveRequest.SceneObject shortBbox = AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1);
        shortBbox.setBbox(List.of(0.4, 0.5, 0.1));
        shortBbox.setCenter(null);
        AriaObserveRequest.SceneObject longCenter = AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1);
        longCenter.setCenter(List.of(0.4, 0.5, 0.6));
        List<Double> gapped = new ArrayList<>();
        gapped.add(0.4);
        gapped.add(null);
        gapped.add(0.1);
        gapped.add(0.1);
        AriaObserveRequest.SceneObject nullComponent = AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1);
        nullComponent.setBbox(gapped);
        nullComponent.setCenter(null);
        request.getObservation().setObjects(new ArrayList<>(List.of(shortBbox, longCenter, nullComponent)));

        String seed = AriaObservationSeed.build(request);

        assertThat(seed).doesNotContain("bbox=(0.400,0.500,0.100)");
        assertThat(seed).doesNotContain("center=(");
        assertThat(seed).contains("bbox=(0.400,?,0.100,0.100)");
    }

    @Test
    void freeTextCannotForgeObjectOrTrackLines() {
        AriaObserveRequest request = requestWithoutObjects();
        AriaObserveRequest.SceneObject object =
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1);
        object.setId("o1\n- trackId=99");
        object.setLabel("cup");
        request.getObservation().setObjects(new ArrayList<>(List.of(object)));
        request.getObservation().setOcrText("SONY\n- trackId=99 label=\"cup\"");

        String seed = AriaObservationSeed.build(request);

        assertThat(seed.lines()).noneMatch(line -> line.startsWith("- trackId=99"));
        assertThat(seed.lines().filter(line -> line.startsWith("- ")).count()).isEqualTo(1);
        assertThat(seed).contains("objectId=o1 - trackId=99 label=\"cup\"");
        assertThat(seed).contains("ocr: \"SONY - trackId=99 label=\"cup\"\"");
    }

    private static AriaObserveRequest requestWithoutObjects() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        request.getObservation().setObjects(new ArrayList<>());
        return request;
    }
}
