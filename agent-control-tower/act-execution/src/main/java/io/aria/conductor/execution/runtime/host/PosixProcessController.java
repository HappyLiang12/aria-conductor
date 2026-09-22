package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * POSIX ownership control: identity-validated descendant-tree enumeration with
 * per-record {@code SIGSTOP}/{@code SIGCONT} for pause/resume and {@code SIGKILL}
 * for bounded termination, matching the recorded POSIX control technique
 * (Task 1 ran its live-core run on Windows; the POSIX path mirrors the same
 * identity-and-enumeration discipline, and is covered by the integration test on
 * a POSIX test host).
 *
 * <p>The creation identity of a POSIX process is its OS start time combined with
 * the PID; a record is acted on only when it is the record this controller bound
 * to a launch of its own (see {@link #owns}) and the process currently holding
 * that PID still has the recorded start time. A record reconstructed from a
 * persisted ownership identity has no binding and is refused. There is no
 * process-group trick here
 * deliberately: a Java child inherits the backend's process group, so a group
 * signal would be able to reach the backend itself.
 *
 * <p>Signals are delivered through {@code /bin/sh -c 'kill -s <sig> "$0"' <pid>}
 * with the PID as an argument -- never by shell string interpolation -- so a
 * process is terminated only after its identity was verified, and a launched
 * process never becomes a shell command line.
 *
 * <p>{@link #stop} quiesces before it kills: the enumerated tree is suspended
 * (SIGSTOP) until a re-enumeration is stable, and only then is the whole set
 * terminated (deepest first). A suspended process cannot spawn, so the sweep
 * acts on an enumeration that is complete for the window in which a descendant
 * could otherwise be spawned and orphaned, and the proof requires every record
 * of that enumeration -- the root included -- to be verified gone. A root that
 * vanishes before this stop killed it is refused instead: what it orphaned can
 * never be enumerated again. A refused stop continues the suspensions it
 * applied itself, so it leaves the tree as it found it.
 *
 * <p>Recorded limitation (identical to the Windows evidence): the descendant
 * enumeration is validated at action time and it is not a kernel-enforced
 * boundary. A descendant that re-parents away from the enumerated tree cannot
 * be attributed to the run and is therefore never touched; the stop proof is
 * refused whenever any discovered owned record cannot be verified stopped, and a
 * record is never inferred from a name or a bare PID.
 */
final class PosixProcessController implements OwnedProcessController {

    /** The verified technique label recorded on every owned process. */
    static final String TECHNIQUE = "identity-validated descendant-tree enumeration (pid + start time) "
            + "+ SIGSTOP/SIGCONT/SIGKILL";

    private static final Path DEFAULT_SHELL = Path.of("/bin/sh");
    private static final Duration SIGNAL_TIMEOUT = Duration.ofSeconds(30);
    private static final long SETTLE_MILLIS = 50;
    private static final int MAX_ROUNDS = 400;
    private static final boolean PROC_FS_AVAILABLE = Files.isDirectory(Path.of("/proc"));

    private final Path shell;
    private final Map<UUID, OwnedProcess> started = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> suspensions = new ConcurrentHashMap<>();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "host-process-control");
        thread.setDaemon(true);
        return thread;
    });

    PosixProcessController() {
        this(DEFAULT_SHELL);
    }

    /** Explicit shell host; an unusable host fails the start, never the technique. */
    PosixProcessController(Path shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    // ------------------------------------------------------------------ port

    @Override
    public OwnedProcess start(UUID runId, LaunchProfile profile) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(profile, "profile");
        requireSignalHost();
        Process process = OwnedProcessController.launch(profile);
        try {
            String creation = creationOf(process.pid()).orElseThrow(() -> new IllegalStateException(
                    "The launched runtime (pid " + process.pid() + ") vanished before its start identity"
                            + " could be recorded"));
            OwnedProcess owned = new OwnedProcess(runId, UUID.randomUUID().toString(), process.pid(), creation,
                    supervisorIdentity(), TECHNIQUE, process);
            started.put(runId, owned);
            return owned;
        } catch (RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    @Override
    public boolean owns(OwnedProcess process) {
        if (process == null) {
            return false;
        }
        OwnedProcess known = started.get(process.runId());
        if (known == null) {
            // No in-memory binding for this run: the record was reconstructed
            // from the persisted identity (or never belonged to this
            // controller), so its ownership nonce cannot be verified against a
            // launch. Fail closed -- adopting or reaping a surviving run after
            // a restart belongs to the recovery coordinator, with run-store
            // evidence, never to a durable record plus an OS look-up.
            return false;
        }
        if (!known.ownershipNonce().equals(process.ownershipNonce())
                || known.rootPid() != process.rootPid()
                || !known.rootCreationIdentity().equals(process.rootCreationIdentity())) {
            return false;
        }
        return rootMatches(process);
    }

    @Override
    public StopProof stop(OwnedProcess process, Instant deadline) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(deadline, "deadline");
        if (!Instant.now().isBefore(deadline)) {
            return new StopProof(process.runId(), false);
        }
        if (!owns(process)) {
            return new StopProof(process.runId(), false);
        }
        Set<String> known = new LinkedHashSet<>();
        known.add(ownedRecord(process));
        Set<String> suspendedHere = new LinkedHashSet<>();
        Map<String, Integer> alreadyPaused = suspensions.get(process.runId());
        try {
            // Quiesce before anything dies. The enumeration is anchored at the
            // recorded root and a suspended process can neither spawn nor exit,
            // so a fully suspended tree yields a stable, complete enumeration
            // and no descendant can be re-parented away from it (nothing has
            // died). A root that vanishes in this phase vanished without a kill
            // from this stop: it orphans descendants that can never be
            // enumerated again, so no proof may be given for what is left.
            boolean quiesced = false;
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<String> live = liveRecords(process.rootPid());
                if (live.isEmpty()) {
                    return refuse(process, suspendedHere, alreadyPaused);
                }
                known.addAll(live);
                List<String> fresh = live.stream()
                        .filter(record -> !suspendedHere.contains(record))
                        .filter(record -> alreadyPaused == null || !alreadyPaused.containsKey(record))
                        .toList();
                if (fresh.isEmpty()) {
                    quiesced = true;
                    break;
                }
                for (String record : fresh) {
                    if (signal(record, "STOP")) {
                        suspendedHere.add(record);
                    }
                }
                if (!Instant.now().isBefore(deadline)) {
                    return refuse(process, suspendedHere, alreadyPaused);
                }
                Thread.sleep(SETTLE_MILLIS);
            }
            if (!quiesced) {
                return refuse(process, suspendedHere, alreadyPaused);
            }
            // Terminate the quiesced tree. The enumeration below was taken while
            // every record was suspended and the root was still alive, so no
            // process could spawn after it: the sweep kills a set that is
            // complete for the window, and the proof requires every record of it
            // -- the root included -- to be verified gone.
            List<String> live = liveRecords(process.rootPid());
            if (live.isEmpty()) {
                return refuse(process, suspendedHere, alreadyPaused);
            }
            known.addAll(live);
            // Deepest first: a child is terminated before its parent, so an
            // unkillable record stays attributable to the run while it is retried.
            for (String record : live) {
                signal(record, "KILL");
            }
            Thread.sleep(SETTLE_MILLIS);
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<String> stragglers = known.stream().filter(PosixProcessController::matches).toList();
                if (stragglers.isEmpty()) {
                    break;
                }
                // Records discovered earlier can outlive the tree walk when an
                // intermediate process died: they are still killed by identity.
                for (String record : stragglers) {
                    signal(record, "KILL");
                }
                if (!Instant.now().isBefore(deadline)) {
                    return refuse(process, suspendedHere, alreadyPaused);
                }
                Thread.sleep(SETTLE_MILLIS);
            }
            if (known.stream().anyMatch(PosixProcessController::matches)) {
                return refuse(process, suspendedHere, alreadyPaused);
            }
            // The run's writers are gone; a ledger entry it no longer has could
            // only mislead a later resume.
            suspensions.remove(process.runId());
            return new StopProof(process.runId(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return refuse(process, suspendedHere, alreadyPaused);
        } catch (RuntimeException e) {
            return refuse(process, suspendedHere, alreadyPaused);
        }
    }

    /**
     * An honest refusal that leaves the tree as it found it: the suspensions
     * this stop applied itself are continued again, while a pause recorded
     * before the stop (which this stop never touched) stays in place.
     */
    private StopProof refuse(OwnedProcess process, Set<String> suspendedHere, Map<String, Integer> alreadyPaused) {
        for (String record : suspendedHere) {
            if (alreadyPaused != null && alreadyPaused.containsKey(record)) {
                continue;
            }
            try {
                signal(record, "CONT");
            } catch (RuntimeException ignored) {
                // best effort: a signal host that fails now cannot be asked again
            }
        }
        return new StopProof(process.runId(), false);
    }

    @Override
    public CompletionStage<ControlAck> pause(OwnedProcess process, Instant deadline) {
        return CompletableFuture.supplyAsync(() -> pauseNow(process, deadline), controlExecutor);
    }

    @Override
    public CompletionStage<ControlAck> resume(OwnedProcess process, Instant deadline) {
        return CompletableFuture.supplyAsync(() -> resumeNow(process, deadline), controlExecutor);
    }

    @Override
    public void release(OwnedProcess process) {
        if (process == null) {
            return;
        }
        started.remove(process.runId());
        suspensions.remove(process.runId());
    }

    @Override
    public void close() {
        controlExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ control

    private ControlAck pauseNow(OwnedProcess process, Instant deadline) {
        if (!Instant.now().isBefore(deadline)) {
            return new ControlAck(ControlState.RUNNING, false);
        }
        if (!owns(process)) {
            return new ControlAck(ControlState.RUNNING, false);
        }
        Map<String, Integer> ledger = suspensions.computeIfAbsent(process.runId(), key -> new LinkedHashMap<>());
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<String> fresh = liveRecords(process.rootPid()).stream()
                        .filter(record -> !ledger.containsKey(record))
                        .toList();
                if (fresh.isEmpty()) {
                    break;
                }
                for (String record : fresh) {
                    if (!signal(record, "STOP")) {
                        return new ControlAck(ControlState.RUNNING, false);
                    }
                    ledger.merge(record, 1, Integer::sum);
                }
                if (!Instant.now().isBefore(deadline)) {
                    return new ControlAck(ControlState.RUNNING, false);
                }
                Thread.sleep(SETTLE_MILLIS);
            }
            // The verified state is the whole owned tree, re-enumerated after the
            // last suspension, so a child spawned during the sweep is included; a
            // run whose root vanished mid-sweep is not a paused run.
            boolean complete = rootMatches(process)
                    && liveRecords(process.rootPid()).stream().allMatch(ledger::containsKey);
            return new ControlAck(complete ? ControlState.PAUSED : ControlState.RUNNING, complete);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ControlAck(ControlState.RUNNING, false);
        } catch (RuntimeException e) {
            return new ControlAck(ControlState.RUNNING, false);
        }
    }

    private ControlAck resumeNow(OwnedProcess process, Instant deadline) {
        if (!Instant.now().isBefore(deadline)) {
            return new ControlAck(ControlState.PAUSED, false);
        }
        if (!owns(process)) {
            return new ControlAck(ControlState.PAUSED, false);
        }
        Map<String, Integer> ledger = suspensions.get(process.runId());
        if (ledger == null || ledger.isEmpty()) {
            return new ControlAck(ControlState.RUNNING, false);
        }
        boolean all = true;
        for (Map.Entry<String, Integer> entry : new ArrayList<>(ledger.entrySet())) {
            boolean resolved = true;
            for (int count = 0; count < entry.getValue(); count++) {
                resolved &= signal(entry.getKey(), "CONT");
            }
            if (resolved) {
                ledger.remove(entry.getKey());
            } else {
                all = false;
            }
        }
        return new ControlAck(all ? ControlState.RUNNING : ControlState.PAUSED, all);
    }

    // ------------------------------------------------------------------ OS identity

    /**
     * Live owned records of the run's tree, deepest first. Every record carries
     * the start time the OS reports <em>now</em>: a PID that was recycled is not
     * this run's process and never enters the owned set.
     */
    private List<String> liveRecords(long rootPid) {
        ProcessHandle root = ProcessHandle.of(rootPid).orElse(null);
        if (root == null || recordOf(rootPid).isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> records = new ArrayList<>();
        Deque<Map.Entry<ProcessHandle, Integer>> queue = new ArrayDeque<>();
        queue.add(Map.entry(root, 0));
        Set<Long> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Map.Entry<ProcessHandle, Integer> node = queue.poll();
            long pid = node.getKey().pid();
            if (!seen.add(pid)) {
                continue;
            }
            String record = recordOf(pid).orElse(null);
            if (record != null) {
                records.add(Map.entry(record, node.getValue()));
            }
            int depth = node.getValue() + 1;
            node.getKey().children().forEach(child -> queue.add(Map.entry(child, depth)));
        }
        records.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
        return records.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * The OS creation identity of a PID as it is right now, or empty when it is
     * no longer a live process. A terminated child whose parent has not reaped it
     * still answers for its PID on a POSIX host, so liveness is decided
     * explicitly (never by the mere presence of the PID): a zombie is not a
     * writer that can still write.
     */
    private static Optional<String> recordOf(long pid) {
        return ProcessHandle.of(pid)
                .filter(PosixProcessController::alive)
                .flatMap(handle -> handle.info().startInstant())
                .map(instant -> pid + "@" + instant.toEpochMilli());
    }

    private static boolean alive(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return false;
        }
        return !PROC_FS_AVAILABLE || !zombie(handle.pid());
    }

    /** True for a process the OS reports as terminated-but-unreaped (state Z or X). */
    private static boolean zombie(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            int close = stat.lastIndexOf(')');
            if (close < 0 || close + 2 >= stat.length()) {
                return true; // unparseable state fails closed
            }
            char state = stat.charAt(close + 2);
            return state == 'Z' || state == 'X';
        } catch (IOException e) {
            return false; // no /proc entry: the handle check above already decided
        }
    }

    private Optional<String> creationOf(long pid) {
        return recordOf(pid).map(record -> record.substring(record.indexOf('@') + 1));
    }

    private static long pidOf(String record) {
        return Long.parseLong(record.substring(0, record.indexOf('@')));
    }

    private static String ownedRecord(OwnedProcess process) {
        return process.rootPid() + "@" + process.rootCreationIdentity();
    }

    /** True while the run's root process still carries its recorded start identity. */
    private static boolean rootMatches(OwnedProcess process) {
        return ownedRecord(process).equals(recordOf(process.rootPid()).orElse(null));
    }

    /** True when the process holding this record's PID is still that same OS process. */
    private static boolean matches(String record) {
        return record.equals(recordOf(pidOf(record)).orElse(null));
    }

    /**
     * Delivers one signal to an identity-verified record. A record whose process
     * is gone or whose PID now belongs to another process is skipped: it is no
     * longer ours to signal, and reporting it as signalled would be a lie.
     */
    private boolean signal(String record, String signal) {
        long pid = pidOf(record);
        if (!matches(record)) {
            return true;
        }
        ProcessBuilder builder = new ProcessBuilder(shell.toString(), "-c",
                // Both POSIX spellings of a signal name ("kill -s STOP" and "kill -STOP");
                // the second only runs when the first is not understood by the signal host.
                "kill -s " + signal + " \"$0\" || kill -" + signal + " \"$0\"", Long.toString(pid));
        try {
            Process signalProcess = builder.start();
            if (!signalProcess.waitFor(SIGNAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                signalProcess.destroyForcibly();
                return false;
            }
            return signalProcess.exitValue() == 0;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to deliver " + signal + " to " + record + ": "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void requireSignalHost() {
        if (!Files.isExecutable(shell)) {
            throw new IllegalStateException("No verified Host process-control technique: the signal host " + shell
                    + " is not executable, and this backend does not fall back to PID-only signalling");
        }
    }

    private static String supervisorIdentity() {
        long jvmPid = ProcessHandle.current().pid();
        return recordOf(jvmPid).orElse(jvmPid + "@unresolved");
    }
}
