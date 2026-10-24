package io.aria.conductor.execution.runtime.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launcher's own ordering, read off its event stream: which fault proves that this
 * run never had a writer, and which proves nothing at all.
 *
 * <p>The class boundary is not a guess about the image. In {@code launch.mjs} every
 * {@code new LaunchFault(...)} is constructed from {@code main()} lines 263-282 (argument
 * parsing 68/70/265, manifest validation 79/82/96-142/272, allowlist and entrypoint
 * resolution 165/170/180/183/189/192, the working-directory check 280), all of which
 * strictly precede {@code launchRun(profile)} at line 289 — and nothing between 289 and
 * the end of {@code main()} constructs one: the fault a live child can still produce is
 * {@code writeRecord} (line 301, after the spawn), which exits as a plain filesystem error
 * (code 70) from the top-level handler at lines 331-338. That handler is also where
 * {@code supervisor.fault} is written, and it writes it to <em>stderr</em>, while
 * {@code supervisor.started} (302), {@code supervisor.child_error} (307),
 * {@code supervisor.listening}/{@code supervisor.ready_timeout} (312) and
 * {@code child.exit} (320) go to stdout.
 */
class LaunchObservationTest {

    private static final String ALLOWLIST_REFUSAL =
            "{\"event\":\"supervisor.fault\",\"name\":\"LaunchFault\","
                    + "\"message\":\"Entrypoint name is not allowlisted by this image: "
                    + "C:\\\\Users\\\\operator\\\\opencode.exe\"}\n";

    private static String spawnLine() {
        return "{\"event\":\"supervisor.started\",\"runId\":\"0e75d7f4\",\"childPid\":41,"
                + "\"recordPath\":\"/home/aria/run/0e75d7f4/launch-record.json\"}\n";
    }

    // ------------------------------------------------------- the destroy-permitting class

    @Test
    void aLaunchFaultWithNoSpawnEvidenceProvesNoWriterOfThisRunEverExisted() {
        LaunchObservation observation = new LaunchObservation();

        // The real refusal the Lead measured: nothing on stdout, the fault on stderr, exit 78.
        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(78);

        assertThat(observation.faultReported()).isTrue();
        assertThat(observation.spawnReported()).isFalse();
        assertThat(observation.faultClass()).isEqualTo(LaunchObservation.FaultClass.NEVER_SPAWNED);
        assertThat(observation.neverSpawnedProven()).isTrue();
        assertThat(observation.launcherVerdict())
                .contains("supervisor.fault")
                .contains("LaunchFault")
                .contains("Entrypoint name is not allowlisted by this image");
    }

    @Test
    void theArgumentShapedLaunchFaultIsTheSamePreSpawnClass() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr("{\"event\":\"supervisor.fault\",\"name\":\"LaunchFault\","
                + "\"message\":\"Usage: launch.mjs --manifest <path>\"}\n");
        observation.commandCompleted(LaunchObservation.PRE_SPAWN_USAGE_EXIT);

        assertThat(observation.faultClass()).isEqualTo(LaunchObservation.FaultClass.NEVER_SPAWNED);
    }

    /**
     * The post-spawn class launch.mjs really has: {@code writeRecord} (line 301) runs
     * <em>after</em> {@code launchRun} spawned the child, and its failure exits as a plain
     * filesystem error. A writer exists, so this is never a licence to destroy unverified.
     */
    @Test
    void aFaultAfterTheLauncherReportedASpawnIsAfterSpawnAndNeverLicensesADestroy() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStdout(spawnLine());
        observation.recordStderr("{\"event\":\"supervisor.fault\",\"name\":\"Error\","
                + "\"message\":\"EACCES: permission denied, rename "
                + "'/home/aria/run/0e75d7f4/launch-record.json.tmp'\"}\n");
        observation.commandCompleted(70);

        assertThat(observation.spawnReported()).isTrue();
        assertThat(observation.faultClass()).isEqualTo(LaunchObservation.FaultClass.AFTER_SPAWN);
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    @Test
    void aLaunchFaultNameCarryingTheWrongExitCodeIsNotEvidenceOfAnything() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(70);

        assertThat(observation.faultClass()).isEqualTo(LaunchObservation.FaultClass.UNREADABLE);
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    @Test
    void aFaultTheLauncherDidNotNameLaunchFaultIsUnreadableEvenWithNoSpawnLine() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr("{\"event\":\"supervisor.fault\",\"name\":\"SyntaxError\","
                + "\"message\":\"Unexpected token in JSON at position 0\"}\n");
        observation.commandCompleted(70);

        assertThat(observation.faultClass()).isEqualTo(LaunchObservation.FaultClass.UNREADABLE);
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    // ------------------------------------------------------- fail-closed reading

    @Test
    void noFaultAtAllProvesNothingAndReportsNoVerdict() {
        LaunchObservation observation = new LaunchObservation();

        observation.commandCompleted(null);

        assertThat(observation.faultReported()).isFalse();
        assertThat(observation.faultClass()).isNull();
        assertThat(observation.neverSpawnedProven()).isFalse();
        assertThat(observation.launcherVerdict()).isEmpty();
    }

    /**
     * An unparseable line is not a "no writers" conclusion. It is also positive spawn
     * evidence: before the spawn the launcher writes only through {@code emit()}
     * (launch.mjs:59-61), and raw bytes appear on its streams only once the child's output
     * is forwarded (launch.mjs:304-305).
     */
    @Test
    void anUnparseableLineIsSpawnEvidenceAndNeverANoWritersConclusion() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStdout("opencode serve listening on 0.0.0.0:4096\n");
        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(78);

        assertThat(observation.spawnReported()).isTrue();
        assertThat(observation.faultClass()).isEqualTo(LaunchObservation.FaultClass.AFTER_SPAWN);
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    @Test
    void anEventOutsideThisContractIsUnreadableAndNeverProvesAbsence() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStdout("{\"event\":\"supervisor.everything_is_fine\"}\n");
        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(78);

        assertThat(observation.faultClass()).isNotEqualTo(LaunchObservation.FaultClass.NEVER_SPAWNED);
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    @Test
    void aBrokenCaptureIsUnknownAndNeverReadsAsAnAbsenceOfWriters() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandAborted();

        assertThat(observation.faultReported()).isFalse();
        assertThat(observation.outcomeKnown()).isFalse();
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    @Test
    void anUnterminatedRemainderOfAFaultLineIsNotAReadVerdict() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr("{\"event\":\"supervisor.fault\",\"name\":\"LaunchFa");
        observation.commandCompleted(78);

        assertThat(observation.faultReported()).isFalse();
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    @Test
    void everyChildEventIsSpawnEvidenceWhateverTheFaultClaims() {
        for (String event : java.util.List.of("supervisor.started", "supervisor.child_error",
                "supervisor.listening", "supervisor.ready_timeout", "child.exit")) {
            LaunchObservation observation = new LaunchObservation();

            observation.recordStdout("{\"event\":\"" + event + "\",\"runId\":\"0e75d7f4\"}\n");
            observation.recordStderr(ALLOWLIST_REFUSAL);
            observation.commandCompleted(78);

            assertThat(observation.spawnReported()).as(event).isTrue();
            assertThat(observation.neverSpawnedProven()).as(event).isFalse();
            assertThat(observation.faultClass()).as(event).isEqualTo(LaunchObservation.FaultClass.AFTER_SPAWN);
        }
    }

    /** The same bytes arriving twice (streamed, then accumulated) change no conclusion. */
    @Test
    void theRecordIsMonotoneSoADuplicateDeliveryChangesNothing() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(78);
        observation.commandCompleted(78);

        assertThat(observation.neverSpawnedProven()).isTrue();
    }

    @Test
    void outputPastTheTrackableBoundIsUnreadableRatherThanSilence() {
        LaunchObservation observation = new LaunchObservation();
        String filler = "x".repeat(LaunchObservation.MAX_TRACKED_BYTES);

        observation.recordStdout(filler);
        observation.recordStdout(filler + "\n");
        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(78);

        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    /**
     * The bound is itself an end to the wait: past what this record can hold, nothing more can
     * be concluded, and releasing the waiter is the difference between "do not destroy" now and
     * a window slept out for a stream nobody is reading any more. The safety half is unchanged —
     * an unreadable stream still proves nothing about the writers.
     */
    @Test
    void outputPastTheTrackableBoundEndsTheWaitAndStillProvesNothing() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStdout("x".repeat(LaunchObservation.MAX_TRACKED_BYTES + 1));

        long started = System.nanoTime();
        assertThat(observation.awaitVerdict(Duration.ofSeconds(30)))
                .as("a stream this record cannot read is a verdict to stop waiting for")
                .isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

        observation.recordStderr(ALLOWLIST_REFUSAL);
        observation.commandCompleted(78);
        assertThat(observation.faultClass()).isNotEqualTo(LaunchObservation.FaultClass.NEVER_SPAWNED);
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    // ------------------------------------------------------- the wait the launch path uses

    @Test
    void awaitVerdictWakesOnTheFirstVerdictAndNeverOnTheChild() throws Exception {
        LaunchObservation observation = new LaunchObservation();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            observation.recordStderr(ALLOWLIST_REFUSAL);
        });

        assertThat(observation.awaitVerdict(Duration.ofSeconds(5))).isTrue();
        assertThat(observation.faultReported()).isTrue();
    }

    @Test
    void awaitVerdictEndsWithTheWindowWhenTheLauncherSaysNothing() {
        LaunchObservation observation = new LaunchObservation();

        long started = System.nanoTime();

        assertThat(observation.awaitVerdict(Duration.ofMillis(120))).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("a silent launcher is awaited for its window, not for its child")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void awaitVerdictReturnsImmediatelyOnceASpawnWasReported() {
        LaunchObservation observation = new LaunchObservation();
        observation.recordStdout(spawnLine());

        long started = System.nanoTime();

        assertThat(observation.awaitVerdict(Duration.ofSeconds(30))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    /**
     * A live read must not depend on the newline: the SDK hands the command's bytes to the
     * handler verbatim ({@code ExecutionEventDispatcher} passes {@code eventNode.getText()}
     * through, adding and stripping nothing), so whether a chunk carries its terminator is
     * execd's choice, not the launcher's or the SDK's. A spawn event held in the buffer for
     * want of a newline would only be read when the command exits — which a successful
     * launch's never does inside the window — and every healthy run would pay the window.
     */
    @Test
    void aSpawnEventArrivingWithoutItsNewlineIsStillReadLive() {
        LaunchObservation observation = new LaunchObservation();

        long started = System.nanoTime();
        observation.recordStdout(spawnLine().stripTrailing());

        assertThat(observation.spawnReported())
                .as("supervisor.started (launch.mjs:302) is a verdict the moment it arrives")
                .isTrue();
        assertThat(observation.verdictReady()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    /** The same framing for the refusal: the prompt verdict is prompt whatever its terminator. */
    @Test
    void aFaultEventArrivingWithoutItsNewlineIsStillTheLaunchersVerdict() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStderr(ALLOWLIST_REFUSAL.stripTrailing());

        assertThat(observation.faultReported()).isTrue();
        assertThat(observation.verdictReady()).isTrue();

        observation.commandCompleted(78);
        assertThat(observation.neverSpawnedProven()).isTrue();
    }

    /**
     * The pair of the tests above: bytes that are not yet a whole event are not a verdict.
     * Reading a partial JSON line as if it were one is exactly how a parse gap could become
     * a "no writers exist" conclusion.
     */
    @Test
    void aSplitEventIsHeldUntilItIsWhollyRead() {
        LaunchObservation observation = new LaunchObservation();

        observation.recordStdout("{\"event\":\"supervisor.sta");
        assertThat(observation.verdictReady()).as("a fragment is not an event").isFalse();

        observation.recordStdout("rted\",\"runId\":\"0e75d7f4\",\"childPid\":41}");
        assertThat(observation.spawnReported()).as("the whole event, reassembled").isTrue();
    }
}
