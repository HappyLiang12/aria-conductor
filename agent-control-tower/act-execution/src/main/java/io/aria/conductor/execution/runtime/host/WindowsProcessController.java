package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Windows ownership control built on the technique Task 1 verified on the live
 * core (docs/reviews/2026-09-22-agent-core-capability-evidence.md):
 * identity-validated descendant-tree enumeration (pid + creation time) with
 * {@code NtSuspendProcess}/{@code NtResumeProcess} for pause/resume and
 * {@code TerminateProcess} for bounded termination.
 *
 * <p>A long-lived PowerShell "supervisor" is created <em>before</em> the core:
 * its script creates the run's job object first -- with
 * {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, exactly as Task 1's committed
 * harness does -- and prints a ready line that the controller awaits before it
 * launches anything. Job membership is then requested for the core PID the
 * moment the OS returned it. Two limits are recorded honestly: membership is
 * best-effort (on the Task 1 live core the assignment was refused with
 * {@code assign_failed:5} and the surviving technique was the enumerated
 * ownership), and the residual window between {@code CreateProcess} and the
 * assignment is not job-covered -- a descendant spawned inside it is caught by
 * the identity-validated enumerated sweep that carries the stop proof. The
 * kill-on-close limit is the backstop for a hard JVM or supervisor death: when
 * the last handle to the job closes, the OS terminates whatever member is left.
 * The supervisor is the OS authority that verifies a process record: it resolves
 * a PID's creation identity through Toolhelp32 + {@code GetProcessTimes} (plus
 * {@code GetExitCodeProcess} for liveness) and refuses to act when the recorded
 * creation identity does not match the process currently holding that PID.
 *
 * <p>Every stop proof carries a <b>job-membership completeness net</b>, because
 * the enumerated ownership alone cannot see a descendant whose intermediate
 * parent died before the first enumeration (it is unreachable from the root's
 * tree walk while it keeps writing). The job is shared by every run this
 * controller supervises, so the net classifies every live member:
 * <ul>
 *   <li>a member that is one of <em>this</em> run's discovered records was killed
 *       by identity like every other writer;</li>
 *   <li>a member that belongs to another live run's enumerated tree is that
 *       run's writer: it is deliberately left alive and excluded from this run's
 *       proof (the per-run isolation ruling -- a per-run stop acts per run);</li>
 *   <li>any member that is neither is unaccounted for and the proof
 *       <b>fails closed</b> ({@code allWritersStopped=false}) instead of
 *       certifying a workspace a live, unreachable writer can still mutate.</li>
 * </ul>
 *
 * <p>Every command is a synchronous JSON-free line exchange; a supervisor that
 * is missing, dead or silent fails the operation explicitly rather than
 * degrading to PID signalling. PowerShell, kernel32 and ntdll are the OS's own
 * components -- nothing is installed or downloaded.
 */
final class WindowsProcessController implements OwnedProcessController {

    /** The verified technique label recorded on every owned process. */
    static final String TECHNIQUE = "windows-job-object(backstop) + identity-validated descendant-tree "
            + "enumeration (pid + creation time) + NtSuspendProcess/NtResumeProcess/TerminateProcess";

    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);
    private static final long SETTLE_MILLIS = 50;
    private static final int MAX_ROUNDS = 400;
    private static final String WORKSPACE_POWERSHELL =
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    private final Path powershell;
    private final Map<UUID, OwnedProcess> started = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> suspensions = new ConcurrentHashMap<>();
    private final Object supervisorLock = new Object();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "host-process-control");
        thread.setDaemon(true);
        return thread;
    });

    private Supervisor supervisor;

    WindowsProcessController() {
        this(defaultPowerShell().orElseThrow(() -> new IllegalStateException(
                "No verified Host process-control technique: no PowerShell host (powershell.exe/pwsh.exe) found")));
    }

    /** Explicit supervisor host; an unusable host fails the start, never the technique. */
    WindowsProcessController(Path powershell) {
        this.powershell = Objects.requireNonNull(powershell, "powershell");
    }

    // ------------------------------------------------------------------ port

    @Override
    public OwnedProcess start(UUID runId, LaunchProfile profile) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(profile, "profile");
        // The run's job must exist before the core does: the supervisor's script
        // creates the job object (with kill-on-close) before it prints the ready
        // line, and this constructor call awaits that line -- so the only
        // unsupervised window left is CreateProcess-to-assign, which the
        // identity-validated enumerated sweep covers.
        Supervisor current = supervisor();
        Process process = OwnedProcessController.launch(profile);
        try {
            String jobMembership = current.command("assign " + process.pid());
            String creation = creationOf(process.pid()).orElseThrow(() -> new IllegalStateException(
                    "The launched runtime (pid " + process.pid() + ") vanished before its creation identity"
                            + " could be recorded"));
            OwnedProcess owned = new OwnedProcess(runId, UUID.randomUUID().toString(), process.pid(), creation,
                    current.selfIdentity(), TECHNIQUE + " [job-backstop=assign:" + jobMembership + "]",
                    process);
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
        try {
            return rootMatches(process);
        } catch (RuntimeException e) {
            return false; // no verified supervisor, no ownership
        }
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
        List<String> known = new ArrayList<>();
        known.add(recordOf(process));
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<String> live = liveRecords(process.rootPid());
                if (live.isEmpty()) {
                    break;
                }
                for (String record : live) {
                    if (!known.contains(record)) {
                        known.add(record);
                    }
                }
                operate("kill", live);
                if (!Instant.now().isBefore(deadline)) {
                    return new StopProof(process.runId(), false);
                }
                Thread.sleep(SETTLE_MILLIS);
            }
            // Records discovered earlier can outlive the tree walk when an
            // intermediate process died: they are still killed by identity.
            List<String> stragglers = known.stream().filter(record -> "match".equals(verdictOf(record))).toList();
            if (!stragglers.isEmpty()) {
                operate("kill", stragglers);
                Thread.sleep(SETTLE_MILLIS);
            }
            // Per-run isolation ruling (Task 13): the job object is shared by every run
            // this controller supervises, so a per-run stop never issues a job-wide kill
            // and never lets a foreign run's membership decide this run's proof. Job
            // members that ARE this run's own records (pid + creation identity verified
            // above) are killed by identity like every other writer; the job's
            // kill-on-close limit remains the OS backstop for a hard JVM death and is
            // never an operation of a per-run stop.
            List<String> ownJobSurvivors = jobMembers().stream().filter(known::contains).toList();
            if (!ownJobSurvivors.isEmpty()) {
                operate("kill", ownJobSurvivors);
                Thread.sleep(SETTLE_MILLIS);
            }
            boolean clean = liveRecords(process.rootPid()).isEmpty();
            for (String record : known) {
                clean &= !"match".equals(verdictOf(record));
            }
            Thread.sleep(SETTLE_MILLIS);
            clean &= liveRecords(process.rootPid()).isEmpty();
            // The job-membership completeness net (class javadoc): the enumerated tree
            // cannot see a descendant whose intermediate parent died before the first
            // enumeration, so the job inventory is the net that catches one. This run's
            // own members are covered by the checks above; another live run's members are
            // that run's writers -- left alone and excluded from this proof; anything
            // unaccounted for fails the proof closed.
            clean &= jobMembershipIsAccounted(process, known);
            return new StopProof(process.runId(), clean);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StopProof(process.runId(), false);
        } catch (RuntimeException e) {
            return new StopProof(process.runId(), false);
        }
    }

    /**
     * Classifies every live member of the shared job against this run's proof. A
     * member that is one of this run's own discovered records was killed by
     * identity (its liveness is already part of the proof); a member of another
     * <em>live</em> run's enumerated tree is that run's writer and is excluded;
     * any other member is unaccounted for and fails the proof closed. Nothing
     * but this run's own records is ever signalled here.
     */
    private boolean jobMembershipIsAccounted(OwnedProcess process, List<String> ownRecords) {
        List<String> foreignRunTrees = new ArrayList<>();
        for (OwnedProcess other : started.values()) {
            if (!other.runId().equals(process.runId()) && rootMatches(other)) {
                foreignRunTrees.addAll(liveRecords(other.rootPid()));
            }
        }
        for (String member : jobMembers()) {
            if (ownRecords.contains(member) || foreignRunTrees.contains(member)) {
                continue;
            }
            return false;
        }
        return true;
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
        synchronized (supervisorLock) {
            if (started.isEmpty() && supervisor != null) {
                // No run is supervised any more: closing the channel closes the last
                // handle to the job object, and its kill-on-close limit terminates
                // whatever member is still alive. That is the OS backstop for a hard
                // JVM death; a caller that stops writers first never notices it.
                supervisor.close();
                supervisor = null;
            }
        }
    }

    @Override
    public void close() {
        synchronized (supervisorLock) {
            if (supervisor != null) {
                supervisor.close();
                supervisor = null;
            }
        }
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
                Map<String, String> results = operate("suspend", fresh);
                // The batch is applied inside the supervisor record by record: a
                // record that exits or is recycled in between reports
                // "gone"/"mismatch" while the later records were still suspended.
                // Merging every applied suspension BEFORE evaluating the batch is
                // what keeps the ledger exact: the aborted batch is a partial
                // state that a later resume repairs, never an untracked one.
                boolean batchComplete = true;
                for (Map.Entry<String, String> result : results.entrySet()) {
                    if ("ok".equals(result.getValue())) {
                        ledger.merge(result.getKey(), 1, Integer::sum);
                    } else {
                        batchComplete = false;
                    }
                }
                if (!batchComplete) {
                    return new ControlAck(ControlState.RUNNING, false);
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
        boolean all = rootMatches(process);
        for (Map.Entry<String, Integer> entry : new ArrayList<>(ledger.entrySet())) {
            boolean resolved = true;
            for (int count = 0; count < entry.getValue(); count++) {
                String applied = operate("resume", List.of(entry.getKey())).get(entry.getKey());
                // NtResumeProcess decrements the suspend count: every recorded
                // suspension needs its own resume or the tree stays suspended.
                resolved &= "ok".equals(applied) || "gone".equals(applied)
                        || (applied != null && applied.startsWith("mismatch"));
            }
            if (resolved) {
                ledger.remove(entry.getKey());
            } else {
                all = false;
            }
        }
        return new ControlAck(all ? ControlState.RUNNING : ControlState.PAUSED, all);
    }

    // ------------------------------------------------------------------ supervisor

    private String command(String line) {
        return supervisor().command(line);
    }

    private Supervisor supervisor() {
        synchronized (supervisorLock) {
            if (supervisor == null || !supervisor.isAlive()) {
                supervisor = new Supervisor();
            }
            return supervisor;
        }
    }

    private Optional<String> creationOf(long pid) {
        String payload = command("identity " + pid);
        if ("gone".equals(payload)) {
            return Optional.empty();
        }
        int at = payload.indexOf('@');
        return at > 0 && at < payload.length() - 1 ? Optional.of(payload.substring(at + 1)) : Optional.empty();
    }

    private String verdict(long pid, String creationIdentity) {
        return command("record " + pid + "@" + creationIdentity);
    }

    /** True while the run's root process still carries its recorded creation identity. */
    private boolean rootMatches(OwnedProcess process) {
        return "match".equals(verdict(process.rootPid(), process.rootCreationIdentity()));
    }

    private String verdictOf(String record) {
        return command("record " + record);
    }

    /** Live owned records of the run's tree, deepest first, each identity-validated by the OS. */
    private List<String> liveRecords(long rootPid) {
        String payload = command("tree " + rootPid);
        List<Map.Entry<String, Integer>> records = new ArrayList<>();
        for (String entry : payload.split(";", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split("@", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("Malformed supervisor tree entry: " + entry);
            }
            records.add(Map.entry(parts[0] + "@" + parts[1], Integer.parseInt(parts[2])));
        }
        records.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
        return records.stream().map(Map.Entry::getKey).toList();
    }

    private List<String> jobMembers() {
        List<String> members = new ArrayList<>();
        for (String record : command("pids").split(";", -1)) {
            if (!record.isBlank()) {
                members.add(record);
            }
        }
        return members;
    }

    private Map<String, String> operate(String action, List<String> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        String payload = command(action + " " + String.join(";", records));
        Map<String, String> results = new LinkedHashMap<>();
        for (String entry : payload.split(";", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            int at = entry.indexOf(':');
            if (at < 0) {
                throw new IllegalStateException("Malformed supervisor " + action + " result: " + entry);
            }
            results.put(entry.substring(0, at), entry.substring(at + 1));
        }
        return results;
    }

    private static String recordOf(OwnedProcess process) {
        return process.rootPid() + "@" + process.rootCreationIdentity();
    }

    private static Optional<Path> defaultPowerShell() {
        List<Path> candidates = new ArrayList<>();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            candidates.add(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"));
        }
        candidates.add(Path.of(WORKSPACE_POWERSHELL));
        candidates.add(Path.of("powershell.exe"));
        candidates.add(Path.of("pwsh.exe"));
        return candidates.stream().filter(WindowsProcessController::resolvable).findFirst();
    }

    private static boolean resolvable(Path candidate) {
        if (candidate.getNameCount() == 1) {
            return onPath(candidate.getFileName().toString());
        }
        return Files.isRegularFile(candidate);
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(";")) {
            if (!directory.isBlank() && Files.isRegularFile(Path.of(directory, executable))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The run's OS supervision channel: one PowerShell process holding the job
     * object and answering identity/termination/suspension commands.
     */
    private final class Supervisor implements AutoCloseable {

        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final StringBuilder diagnostics = new StringBuilder();
        private final Path scriptFile;
        private final String selfIdentity;

        Supervisor() {
            // The script is handed over as a file, never as `-Command <text>`: a
            // Windows command line cannot carry a script with embedded quotes
            // reliably (Java escapes them as \" and PowerShell 5.1 then parses the
            // line differently, which silently corrupts the script).
            try {
                scriptFile = Files.createTempFile("aria-host-supervisor-", ".ps1");
                Files.writeString(scriptFile, SCRIPT, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to stage the Host control supervisor script: "
                        + e.getMessage(), e);
            }
            ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString());
            try {
                process = builder.start();
            } catch (IOException e) {
                deleteScript();
                throw new IllegalStateException("Host control supervisor unavailable (" + powershell + "): "
                        + e.getMessage(), e);
            }
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            startReader("host-supervisor-out", process.getInputStream(), this::readLine);
            startReader("host-supervisor-err", process.getErrorStream(), this::readDiagnostic);
            String ready = awaitReady();
            if (ready == null || !ready.startsWith("ok|")) {
                String failure = "Host control supervisor failed to start (" + powershell + "): " + ready
                        + " " + stderr();
                close();
                throw new IllegalStateException(failure);
            }
            selfIdentity = ready.substring(3).trim();
        }

        /** Wait for the ready line, failing as soon as the supervisor is gone. */
        private String awaitReady() {
            Instant limit = Instant.now().plus(START_TIMEOUT);
            while (Instant.now().isBefore(limit)) {
                String line;
                try {
                    line = lines.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (line != null) {
                    return line;
                }
                if (!process.isAlive()) {
                    return null;
                }
            }
            return null;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        String selfIdentity() {
            return selfIdentity;
        }

        synchronized String command(String line) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Host control supervisor exited: " + stderr());
            }
            try {
                input.write(line);
                input.write('\n');
                input.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Host control supervisor input failed: " + e.getMessage(), e);
            }
            String response = awaitLine(COMMAND_TIMEOUT);
            if (response == null) {
                throw new IllegalStateException("Host control supervisor gave no response to '" + line + "'");
            }
            if (response.startsWith("err|")) {
                throw new IllegalStateException("Host control supervisor refused '" + line + "': "
                        + response.substring(4));
            }
            if (!response.startsWith("ok|")) {
                throw new IllegalStateException("Unexpected host control supervisor response: " + response);
            }
            return response.substring(3);
        }

        private void readLine(String line) {
            lines.offer(line);
        }

        private synchronized void readDiagnostic(String line) {
            diagnostics.append(line).append('\n');
            if (diagnostics.length() > 8192) {
                diagnostics.delete(0, diagnostics.length() - 8192);
            }
        }

        private String awaitLine(Duration timeout) {
            try {
                return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the host control supervisor", e);
            }
        }

        private String stderr() {
            String recorded;
            synchronized (this) {
                recorded = diagnostics.toString().trim();
            }
            return recorded.isEmpty() ? "(no supervisor diagnostics)" : "stderr=" + recorded;
        }

        private void startReader(String name, java.io.InputStream stream,
                java.util.function.Consumer<String> consumer) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        consumer.accept(line);
                    }
                } catch (IOException ignored) {
                    // the channel closes with the supervisor
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            try {
                if (process.isAlive()) {
                    input.write("exit");
                    input.write('\n');
                    input.flush();
                }
            } catch (IOException ignored) {
                // the supervisor may already be gone
            }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            deleteScript();
        }

        private void deleteScript() {
            try {
                if (scriptFile != null) {
                    Files.deleteIfExists(scriptFile);
                }
            } catch (IOException ignored) {
                // a temp script left behind carries no secret and no authority
            }
        }
    }

    /**
     * The supervisor script. It mirrors the Task 1 harness supervisor
     * (`e2e/agent-core/probe-native.mjs`, created for the recorded run): the same
     * job object created with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} through
     * the extended-limit structure (class 9), the same identity-validated
     * descendant-tree enumeration (pid + creation time) and the same
     * NtSuspendProcess/NtResumeProcess and TerminateProcess primitives. The
     * kill-on-close limit is the recorded backstop for a hard JVM or supervisor
     * death: the last handle to the job closing takes any surviving member down,
     * which the enumerated sweep cannot provide for a supervisor that is gone.
     * The creation identity is read through Toolhelp32 + GetProcessTimes instead
     * of WMI's Win32_Process: identical data, but a per-command WMI query costs
     * seconds per call, which would make a bounded stop unbounded in practice.
     */
    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $src = @'
            using System;
            using System.Collections.Generic;
            using System.Runtime.InteropServices;
            public static class AriaHostControl {
              [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
              public static extern IntPtr CreateJobObject(IntPtr a, string n);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern bool SetInformationJobObject(IntPtr j, int cls, IntPtr info, uint len);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern bool AssignProcessToJobObject(IntPtr j, IntPtr p);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern bool TerminateJobObject(IntPtr j, uint code);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern bool QueryInformationJobObject(IntPtr j, int cls, IntPtr info, uint len, IntPtr ret);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern bool CloseHandle(IntPtr h);
              [DllImport("kernel32.dll", SetLastError=true)]
              public static extern bool TerminateProcess(IntPtr h, uint code);
              [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr h);
              [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr h);
              [DllImport("kernel32.dll", SetLastError=true)]
              static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint processId);
              [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
              static extern bool Process32FirstW(IntPtr snapshot, ref PROCESSENTRY32W entry);
              [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
              static extern bool Process32NextW(IntPtr snapshot, ref PROCESSENTRY32W entry);
              [DllImport("kernel32.dll", SetLastError=true)]
              static extern bool GetProcessTimes(IntPtr h, out FILETIME creation, out FILETIME exit,
                                                 out FILETIME kernel, out FILETIME user);
              [DllImport("kernel32.dll", SetLastError=true)]
              static extern bool GetExitCodeProcess(IntPtr h, out uint exitCode);

              const int BasicPidList = 3;
              const int ExtLimit = 9;
              const uint KillOnClose = 0x2000;
              const uint Access = 0x0001 | 0x0100 | 0x0400 | 0x0800 | 0x1000;
              const uint QueryLimited = 0x1000;
              const uint SnapProcess = 0x2;
              const uint StillActive = 259;

              [StructLayout(LayoutKind.Sequential)]
              public struct FILETIME { public uint Low; public uint High; }

              [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
              public struct PROCESSENTRY32W {
                public uint dwSize; public uint cntUsage; public uint th32ProcessID; public IntPtr th32DefaultHeapID;
                public uint th32ModuleID; public uint cntThreads; public uint th32ParentProcessID;
                public int pcPriClassBase; public uint dwFlags;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szExeFile;
              }

              [StructLayout(LayoutKind.Sequential)]
              public struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
                public long PerProcessUserTimeLimit; public long PerJobUserTimeLimit; public uint LimitFlags;
                public UIntPtr MinimumWorkingSetSize; public UIntPtr MaximumWorkingSetSize;
                public uint ActiveProcessLimit; public UIntPtr Affinity;
                public uint PriorityClass; public uint SchedulingClass;
              }
              [StructLayout(LayoutKind.Sequential)]
              public struct IO_COUNTERS {
                public ulong ReadOperationCount; public ulong WriteOperationCount; public ulong OtherOperationCount;
                public ulong ReadTransferCount; public ulong WriteTransferCount; public ulong OtherTransferCount;
              }
              [StructLayout(LayoutKind.Sequential)]
              public struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
                public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation; public IO_COUNTERS IoInfo;
                public UIntPtr ProcessMemoryLimit; public UIntPtr JobMemoryLimit;
                public UIntPtr PeakProcessMemoryUsed; public UIntPtr PeakJobMemoryUsed;
              }

              public static IntPtr CreateJob() {
                IntPtr h = CreateJobObject(IntPtr.Zero, null);
                if (h == IntPtr.Zero) { throw new Exception("CreateJobObject failed " + Marshal.GetLastWin32Error()); }
                int len = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
                IntPtr p = Marshal.AllocHGlobal(len);
                try {
                  var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
                  info.BasicLimitInformation.LimitFlags = KillOnClose;
                  Marshal.StructureToPtr(info, p, false);
                  if (!SetInformationJobObject(h, ExtLimit, p, (uint)len)) {
                    throw new Exception("SetInformationJobObject failed " + Marshal.GetLastWin32Error());
                  }
                } finally { Marshal.FreeHGlobal(p); }
                return h;
              }
              public static string Assign(IntPtr j, int pid) {
                IntPtr hp = OpenProcess(Access, false, pid);
                if (hp == IntPtr.Zero) { return "open_failed:" + Marshal.GetLastWin32Error(); }
                try { return AssignProcessToJobObject(j, hp) ? "ok" : "assign_failed:" + Marshal.GetLastWin32Error(); }
                finally { CloseHandle(hp); }
              }
              public static int[] JobPids(IntPtr j) {
                int len = 64 * 1024;
                IntPtr p = Marshal.AllocHGlobal(len);
                try {
                  if (!QueryInformationJobObject(j, BasicPidList, p, (uint)len, IntPtr.Zero)) { return new int[0]; }
                  int count = Marshal.ReadInt32(p, 0);
                  int[] pids = new int[count];
                  for (int i = 0; i < count; i++) { pids[i] = Marshal.ReadInt32(p, 8 + i * IntPtr.Size); }
                  return pids;
                } finally { Marshal.FreeHGlobal(p); }
              }
              public static string Operate(int pid, int op) {
                IntPtr hp = OpenProcess(Access, false, pid);
                if (hp == IntPtr.Zero) { return "open_failed:" + Marshal.GetLastWin32Error(); }
                try {
                  if (op == 1) { return NtSuspendProcess(hp) == 0 ? "ok" : "nt_failed"; }
                  if (op == 2) { return NtResumeProcess(hp) == 0 ? "ok" : "nt_failed"; }
                  return TerminateProcess(hp, 1) ? "ok" : "terminate_failed:" + Marshal.GetLastWin32Error();
                } finally { CloseHandle(hp); }
              }
              // The creation identity of a live PID: FILETIME ticks since 1601 (100 ns), or null when the
              // process is gone. A terminated process with an open handle (every ProcessBuilder child
              // whose Process object is still referenced) keeps answering GetProcessTimes, so liveness
              // is decided by the exit code and never by the mere presence of the PID.
              public static string Creation(int pid) {
                IntPtr hp = OpenProcess(QueryLimited, false, pid);
                if (hp == IntPtr.Zero) { return null; }
                try {
                  uint exitCode;
                  if (!GetExitCodeProcess(hp, out exitCode) || exitCode != StillActive) { return null; }
                  FILETIME creation, exit, kernel, user;
                  if (!GetProcessTimes(hp, out creation, out exit, out kernel, out user)) { return null; }
                  return ((long)(((ulong)creation.High << 32) | creation.Low)).ToString();
                } finally { CloseHandle(hp); }
              }
              // Identity-validated descendant tree of one root: "pid@created@depth" entries.
              public static string[] Tree(int root) {
                IntPtr snapshot = CreateToolhelp32Snapshot(SnapProcess, 0);
                if (snapshot == (IntPtr)(-1)) { throw new Exception("Process snapshot failed " + Marshal.GetLastWin32Error()); }
                try {
                  var children = new Dictionary<int, List<int>>();
                  var entry = new PROCESSENTRY32W();
                  entry.dwSize = (uint)Marshal.SizeOf(typeof(PROCESSENTRY32W));
                  if (Process32FirstW(snapshot, ref entry)) {
                    do {
                      int procId = (int)entry.th32ProcessID;
                      int parent = (int)entry.th32ParentProcessID;
                      List<int> list;
                      if (!children.TryGetValue(parent, out list)) { list = new List<int>(); children[parent] = list; }
                      list.Add(procId);
                    } while (Process32NextW(snapshot, ref entry));
                  }
                  var records = new List<string>();
                  var queue = new Queue<int[]>();
                  var seen = new HashSet<int>();
                  queue.Enqueue(new int[] { root, 0 });
                  while (queue.Count > 0) {
                    int[] node = queue.Dequeue();
                    if (!seen.Add(node[0])) { continue; }
                    string created = Creation(node[0]);
                    if (created != null) { records.Add(node[0] + "@" + created + "@" + node[1]); }
                    List<int> list;
                    if (children.TryGetValue(node[0], out list)) {
                      foreach (int child in list) { queue.Enqueue(new int[] { child, node[1] + 1 }); }
                    }
                  }
                  return records.ToArray();
                } finally { CloseHandle(snapshot); }
              }
            }
            '@
            Add-Type -TypeDefinition $src -Language CSharp
            $job = [AriaHostControl]::CreateJob()

            function Get-SelfRecord {
              return ('{0}@{1}' -f $PID, [AriaHostControl]::Creation($PID))
            }
            function Get-RecordOf([int]$targetPid) {
              $created = [AriaHostControl]::Creation($targetPid)
              if ($null -eq $created) { return $null }
              return ('{0}@{1}' -f $targetPid, $created)
            }
            function Test-Record([string]$record) {
              $parts = $record.Split('@')
              $targetPid = [int]$parts[0]
              $expected = if ($parts.Count -gt 1) { $parts[1] } else { '' }
              $now = Get-RecordOf $targetPid
              if ($null -eq $now) { return 'gone' }
              if ($expected -eq '' -or $now -eq $record) { return 'match' }
              return ('mismatch:' + $now)
            }
            function Invoke-Records([string]$records, [int]$op) {
              $results = @()
              foreach ($record in ($records.Split(';') | Where-Object { $_ -ne '' })) {
                $verdict = Test-Record $record
                if ($verdict -ne 'match') { $results += ('{0}:{1}' -f $record, $verdict); continue }
                $targetPid = [int]($record.Split('@')[0])
                $applied = [AriaHostControl]::Operate($targetPid, $op)
                $results += ('{0}:{1}' -f $record, $applied)
              }
              return ,$results
            }

            [Console]::Out.WriteLine('ok|' + (Get-SelfRecord))
            [Console]::Out.Flush()
            $running = $true
            while ($running) {
              $line = [Console]::In.ReadLine()
              if ($null -eq $line) { break }
              $line = $line.Trim()
              if ($line -eq '') { continue }
              try {
                $parts = $line.Split(' ', 2)
                $cmd = $parts[0]
                $arg = if ($parts.Count -gt 1) { $parts[1] } else { '' }
                switch ($cmd) {
                  'self'     { $r = 'ok|' + (Get-SelfRecord) }
                  'assign'   { $r = 'ok|' + [AriaHostControl]::Assign($job, [int]$arg) }
                  'pids'     {
                    $recs = @()
                    foreach ($member in [AriaHostControl]::JobPids($job)) {
                      $rec = Get-RecordOf ([int]$member)
                      if ($null -ne $rec) { $recs += $rec }
                    }
                    $r = 'ok|' + ($recs -join ';')
                  }
                  'identity' { $rec = Get-RecordOf ([int]$arg); if ($null -eq $rec) { $r = 'ok|gone' } else { $r = 'ok|' + $rec } }
                  'record'   { $r = 'ok|' + (Test-Record $arg) }
                  'tree'     { $records = [AriaHostControl]::Tree([int]$arg); $r = 'ok|' + ($records -join ';') }
                  'suspend'  { $results = Invoke-Records $arg 1; $r = 'ok|' + ($results -join ';') }
                  'resume'   { $results = Invoke-Records $arg 2; $r = 'ok|' + ($results -join ';') }
                  'kill'     { $results = Invoke-Records $arg 3; $r = 'ok|' + ($results -join ';') }
                  'jobkill'  {
                    $members = [AriaHostControl]::JobPids($job)
                    if ($members.Length -gt 0) { [AriaHostControl]::TerminateJobObject($job, 1) | Out-Null }
                    $r = 'ok|' + $members.Length
                  }
                  'exit'     { $running = $false; $r = 'ok|bye' }
                  default    { $r = 'err|unknown command: ' + $cmd }
                }
              } catch {
                $r = 'err|' + ($_.Exception.Message -replace "`r|`n", ' ')
              }
              [Console]::Out.WriteLine($r)
              [Console]::Out.Flush()
            }
            """;
}
