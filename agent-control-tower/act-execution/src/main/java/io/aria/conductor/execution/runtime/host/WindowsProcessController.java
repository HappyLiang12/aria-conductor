package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;
import lombok.extern.slf4j.Slf4j;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>F33, critical (live QA stop proof of run be18e519): a parent link is the
 * creator's pid at creation time and is never updated, so a dead creator's pid may
 * be recycled by a newer, unrelated process. The live failure had the backend JVM
 * and its supervisor -- 12 minutes <em>older</em> than the run root -- adopted as
 * descendants of a freshly created QA root (the backend's dead launcher pid had
 * been reused) and killed by that run's stop proof. The walk therefore validates
 * every parent link against creation order: a process cannot be a child of a
 * process created after it, so a link whose child predates its parent is stale and
 * its subtree is not this root's. A gone node's children are not followed either --
 * an earlier holder of its pid may have created some of them. A live but unreadable
 * holder (protected/elevated) is reported as an {@code unverifiable:<pid>} gap and
 * its subtree is not followed: a proof that meets a gap fails closed instead of
 * silently omitting a subtree it cannot classify. On top of the walk, every tree
 * read drops records older than the run's recorded root and anchors the tree on the
 * root identity the controller verified -- a root pid reused between the ownership
 * verdict and the enumeration fails the read instead of walking a stranger's tree.
 * Every mutating control batch refuses this controller's own JVM, its supervision
 * channel, malformed records and any record that predates the run; the supervisor
 * additionally refuses itself. A refused record is never signalled -- it stays live,
 * so the proof fails closed instead of certifying it stopped. The pid-reuse shape
 * cannot be forced deterministically on a live host: the root boundary and the
 * per-link boundary are unit-tested (the latter through the supervisor's own "link"
 * classifier), the stale-link shape is reproduced deterministically by the
 * fake-supervisor harness, and the walk's positive shape and both self-signal
 * refusals are exercised on the real OS.
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
 *   <li>a member that is none of those is unaccounted for. While this controller
 *       supervises exactly one run, a member created at or after that run's root
 *       is attributed to it, killed by identity and verdict-checked (F25); a
 *       member that survives that check, or one the attribution may not claim,
 *       <b>fails closed</b> ({@code allWritersStopped=false}) instead of
 *       certifying a workspace a live, unreachable writer can still mutate.</li>
 * </ul>
 *
 * <p>R7: a run's tree is sampled while the run is live, so a child that outlives
 * its root (the bridge exits, its CLI child takes a moment to follow) is still one
 * of this run's discovered records at stop time and is killed by identity instead
 * of failing the proof as an unreachable writer. The sampler never builds a
 * supervision channel -- it snapshots the live one and skips when there is none --
 * never signals anything, and keeps a pruned population: an over-cap set spends a
 * bounded probe budget on identities the OS reports gone or recycled (a gone or
 * recycled identity can never account for a live writer), and that budget rotates
 * through the population pass by pass, so a dead record behind a wall of live ones
 * is still reached and evicted instead of sitting behind the same probed head for
 * the life of the run. A live or unverifiable record is never shed: the job
 * inventory is best-effort (the assignment can be refused and breakaway children
 * are invisible to it), so a sampled record can be the only evidence attributing a
 * live child to this run, and shedding it to honour the cap could certify a live
 * writer as stopped. The cap therefore bounds dead evidence only; an over-cap live
 * (or unverifiable) population is retained over the cap.
 *
 * <p>Release-blocking live finding (run 8af5e2a6, WAITING_INPUT cancel): a run's
 * core exiting on its own is a normal lifecycle -- the assistant's clarification
 * question was delivered -- and the run then stays WAITING_INPUT until an operator
 * cancels it. The OS identity of the root is gone, but the in-memory binding
 * ({@link #bound}: nonce, root pid and creation identity of the launch this
 * controller performed) is not, so the stop proof must still run: the root tree
 * is not swept (a gone root's orphaned children still carry its parent pid, but
 * the lookup cannot distinguish them from a recycled pid's tree, so nothing is
 * enumerated or signalled from it), the sampler's observed records are killed
 * and verdict-checked by identity, the inventory still nets unaccounted members,
 * and the proof is true only when every known record is gone or a recycled identity, no
 * unverifiable record remains and nothing is unaccounted for. Only a record that
 * is not this controller's binding is refused without action. {@code owns} keeps
 * requiring a live root, so pause/resume stay refused on a run that is no longer
 * alive. Every false proof is logged (identities only) with the path that fired:
 * binding mismatch, live records remaining, unverifiable records, deadline expiry
 * or an exception -- the live failure produced no diagnostic at all.
 *
 * <p>Residual (review round 6, revised for F25): a writer spawned after the
 * sampler's last pass and before the root exited, and invisible to the job
 * inventory, is not enumerable at proof time; an inventory-visible member either
 * fails the proof closed (see below) or, when this controller supervises exactly
 * one run, is attributed to that run and killed by identity. The live failure
 * was the single-run case: a child whose intermediate parent exited between two
 * 500 ms tree samples (run 4bd06a41, {@code unaccounted=[35224@...]}) is
 * unreachable from the root forever, so the shared job was the only observer of
 * a writer the proof then refused. Attribution is deliberately narrow -- one
 * supervised run only, member creation ticks not older than the root (a process
 * cannot predate the run that spawned it), and no {@code unverifiable} or
 * malformed record -- because the shared-job isolation ruling forbids guessing
 * across runs. With more than one supervised run a concurrent run's writer is
 * indistinguishable from this run's orphan, so the case stays fail-closed; a
 * job-invisible writer remains outside what this proof can observe.
 *
 * <p>Review round 2 tightened two edges of that net. The inventory is now taken
 * <em>twice</em>: a member being killed is still a live process until the OS
 * reports it gone, so it can spawn a child that inherits the shared job after
 * the first inventory, and the repeated inventory fails the proof closed on that
 * child instead of certifying it stopped. And attribution counts launches that
 * are still in flight, not just registered runs: a launch becomes a job member
 * before it is registered, so a concurrent stop must not read it as this run's
 * orphan and kill another run's root. Only {@code pid@creation} identities are
 * ever logged by either path.
 *
 * <p>Every command is a synchronous JSON-free line exchange; a supervisor that
 * is missing, dead or silent fails the operation explicitly rather than
 * degrading to PID signalling. PowerShell, kernel32 and ntdll are the OS's own
 * components -- nothing is installed or downloaded.
 */
@Slf4j
final class WindowsProcessController implements OwnedProcessController {

    /**
     * The verified technique label recorded on every owned process. It is part
     * of the durable ownership identity that the binding column persists, so it
     * stays a compact token; the full technique is described in the class
     * javadoc.
     */
    static final String TECHNIQUE = "windows-job-object+identity-tree+nt-control";

    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);
    private static final long SETTLE_MILLIS = 50;
    private static final int MAX_ROUNDS = 400;
    /**
     * F33: 10 ms in FILETIME ticks, the same creation-order tolerance the supervisor
     * applies per parent link (the "link" protocol test pins the two together). A
     * child can never be created before its parent; the tolerance only absorbs an
     * unforeseen clock-source disagreement -- the F32 measurement of the WMI/kernel
     * delta was under 1 ms, and the live pid reuse that killed the backend was 12
     * minutes, far outside any tolerance. A root pid reused within this window of the
     * recorded creation time would pass the anchor; that requires the recorded root
     * to die and the pid to be reused inside 10 ms, and is accepted as a theoretical
     * residual -- the 10 ms window is the only guard on it.
     */
    static final long CREATION_ORDER_TOLERANCE_TICKS = 100_000L;
    private static final String WORKSPACE_POWERSHELL =
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    private final Path powershell;
    private final Map<UUID, OwnedProcess> started = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> suspensions = new ConcurrentHashMap<>();
    /**
     * R7: records this run's tree has contained while it was still alive. The stop
     * proof's first enumeration walks the tree from the root, so a child that
     * outlives the root (the bridge exits, its CLI child takes a moment to follow)
     * would otherwise be unreachable and fail the proof closed. Sampling while the
     * run is live keeps that child attributable to this run; the proof then kills it
     * by identity like any other writer.
     */
    private final Map<UUID, Set<String>> observedRecords = new ConcurrentHashMap<>();
    /** The largest population one run's sampler has held before pruning it (test-visible peak). */
    private final Map<UUID, Integer> observedPeaks = new ConcurrentHashMap<>();
    /**
     * R7 memory bound: the records one run's sampler remembers. The cap is soft and
     * bounds <em>dead</em> evidence only -- identities the OS reports gone or
     * recycled are pruned, a live or unverifiable record is never shed: in the
     * documented best-effort job configuration (assignment refused, breakaway
     * children invisible) the sampled record is the only attribution evidence for a
     * child unreachable from the root, so shedding it could let a later proof
     * certify a live writer as stopped. An over-cap live population is therefore
     * retained over the cap.
     */
    static final int MAX_OBSERVED_RECORDS = 512;
    /** Probes one over-cap sampling pass spends on records the OS may report gone or recycled. */
    private static final int OBSERVED_PRUNE_PROBES = 32;
    /**
     * R7 (review round 3, P3-a): where the run's next over-cap pass starts probing.
     * The pass budget is small and the population is iteration-ordered, so a pass
     * that always began at the head would spend every budget on the same records:
     * with 32+ live records at the head, the gone records behind them would never be
     * probed and the population would never be pruned. The cursor rotates the start
     * of each pass, so successive passes sweep the whole population. Cleared with the
     * run's population on release/close (and on a rolled-back start).
     */
    private final Map<UUID, Integer> observedProbeCursors = new ConcurrentHashMap<>();
    /**
     * R7 (review round 3, P3-b): the supervisor's verdict prefix for an identity it
     * could not read -- a failed OS query that is not a definite absence. It is
     * deliberately distinct from {@code gone}: an unreadable query is never folded
     * into absence, so the record is retained, never signalled, and a stop proof
     * that meets one fails closed instead of certifying a live writer as stopped.
     */
    private static final String UNVERIFIABLE = "unverifiable";
    private final Object supervisorLock = new Object();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "host-process-control");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService treeSampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "host-process-tree-sampler");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean sampling = false;
    /**
     * R7: close() is terminal. Once it returns, no control path may build a new
     * supervision channel -- a supervisor created after the close would be closed
     * by nobody (leaking its process, job handle and temp script), and its
     * construction blocks for the whole start window inside {@link #supervisorLock}.
     */
    private volatile boolean closed = false;
    /**
     * Test-visible seam for the window between the bind and the terminal-state
     * re-check: production never assigns it. It lets a test deterministically
     * interleave {@code close()} with a {@code start()} that has already bound
     * its run, which the race the re-check defends cannot be forced otherwise --
     * and it hands the bound record to the test, so the rollback itself (not a
     * concurrent {@code close()}) can be shown to have removed every trace of it.
     */
    volatile java.util.function.Consumer<OwnedProcess> afterBind = owned -> { };
    /**
     * Test-visible seam for the window between the root sweep and the straggler
     * scan of {@link #stop}: production never assigns it. The sampler runs on its
     * own thread and can add a record at any point of the proof, so a test can
     * only place that addition deterministically through a seam. It receives the
     * run's live observed set -- the same set the sampler adds to.
     */
    volatile java.util.function.Consumer<Set<String>> afterRootSweep = records -> { };
    /**
     * Test-visible seam for the window after the final job inventory of
     * {@link #stop}: production never assigns it. The straggler kill, the
     * per-record verdicts and the inventory are all round trips after the last
     * population re-read, so a record the sampler adds in this last window was
     * never scanned, killed or verdict-checked -- and a breakaway writer is
     * invisible to the inventory. The seam receives the run's live observed set
     * (the same set the sampler adds to), so a test can place that addition
     * deterministically and assert the proof fails closed on it.
     */
    volatile java.util.function.Consumer<Set<String>> afterFinalInventory = records -> { };
    /**
     * F2 (review round 2): the number of launches that are in flight -- counted from
     * before the OS can make the launch a job member until its binding is registered
     * in {@link #started}. A launch in that window is a live run that the shared job
     * already sees but the controller does not supervise yet; it is not another run's
     * orphan, and attribution must not claim it.
     */
    private final AtomicInteger inFlightLaunches = new AtomicInteger();
    /**
     * Test-visible seam for the window between the shared job admitting a launch
     * ({@code assign}) and the launch being registered in {@link #started}:
     * production never assigns it. In that window the run is a live job member but
     * not yet supervised, so a concurrent stop must not attribute its root as an
     * orphan of another run. The seam receives the launched pid, so a test can hold
     * a launch in the window deterministically.
     */
    volatile java.util.function.IntConsumer afterAssign = pid -> { };
    /**
     * Test-visible seam for the window between the first job inventory of
     * {@link #stop} and the second one: production never assigns it. A member the
     * job admits in that window -- the attributed kill and its verdict round trips
     * are long enough for a process being killed to spawn a child that inherits the
     * shared job -- was never attributed, killed or verdict-checked, so a test
     * places it here deterministically and asserts the second inventory fails the
     * proof closed.
     */
    volatile Runnable afterAttribution = () -> { };
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
    public OwnedProcess start(UUID runId, LaunchProfile profile, RunCoreLog coreLog) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(coreLog, "coreLog");
        if (closed) {
            throw new IllegalStateException(
                    "This Host process controller is closed; no new run can be supervised");
        }
        // F2 (review round 2): a launch is in flight from before the OS can make it a
        // job member until its binding is registered below. A concurrent stop must not
        // read that window as "this controller supervises exactly one run" and attribute
        // the launching run's root as an orphan: the launch is a live writer of another
        // run. The counter therefore covers supervisor()/launch, the assignment, the
        // creation identity and the registration, and is released by the finally --
        // including every rollback path.
        inFlightLaunches.incrementAndGet();
        try {
            // The run's job must exist before the core does: the supervisor's script
            // creates the job object (with kill-on-close) before it prints the ready
            // line, and this constructor call awaits that line -- so the only
            // unsupervised window left is CreateProcess-to-assign, which the
            // identity-validated enumerated sweep covers.
            Supervisor current = supervisor();
            Process process = OwnedProcessController.launch(profile, coreLog);
            OwnedProcess owned = null;
            try {
                String jobMembership = current.command("assign " + process.pid());
                afterAssign.accept((int) process.pid());
                String creation = creationOf(process.pid()).orElseThrow(() -> new IllegalStateException(
                        "The launched runtime (pid " + process.pid() + ") vanished before its creation identity"
                                + " could be recorded"));
                owned = new OwnedProcess(runId, UUID.randomUUID().toString(), process.pid(), creation,
                        current.selfIdentity(), TECHNIQUE + " [job-backstop=assign:" + jobMembership + "]",
                        process);
                started.put(runId, owned);
                Set<String> observed = ConcurrentHashMap.newKeySet();
                observed.add(recordOf(owned));
                observedRecords.put(runId, observed);
                observedPeaks.put(runId, observed.size());
                observedProbeCursors.remove(runId);
                afterBind.accept(owned);
                // close() sets the terminal state before it clears the bindings and
                // cannot be excluded by the launch, so it is re-checked after the bind:
                // reporting success here would hand back a run the kill-on-close job
                // then terminates, and startSampling would be rejected by the shut-down
                // sampler after a partial bind. The rollback in the catch removes the
                // binding and terminates the launched process.
                if (closed) {
                    throw new IllegalStateException(
                            "This Host process controller is closed; no new run can be supervised");
                }
                startSampling();
                return owned;
            } catch (RuntimeException e) {
                if (owned != null) {
                    started.remove(runId, owned);
                    suspensions.remove(runId);
                    observedRecords.remove(runId);
                    observedPeaks.remove(runId);
                    observedProbeCursors.remove(runId);
                }
                process.destroyForcibly();
                throw e;
            }
        } finally {
            inFlightLaunches.decrementAndGet();
        }
    }

    /**
     * True when the record is the binding this controller created for its run at
     * {@link #start}: the in-memory record exists for the run and its ownership
     * nonce, root pid and creation identity all match. Unlike {@link #owns} this
     * does not require the root's creation identity to still be live.
     *
     * <p>That distinction is what the release-blocking WAITING_INPUT finding
     * needed: a run whose core exits on its own -- the assistant's clarification
     * question was delivered and the bridge is gone, a normal lifecycle -- is
     * still this controller's run. Its binding is intact, so its observed writers
     * must still be stopped by identity; refusing at the live-root gate instead
     * left them unkilled and unverified and always failed finalization. A record
     * with no binding for the run, or one whose nonce/pid/creation differ from it,
     * belongs to someone else and is refused by every control path.
     */
    private boolean bound(OwnedProcess process) {
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
        return known.ownershipNonce().equals(process.ownershipNonce())
                && known.rootPid() == process.rootPid()
                && known.rootCreationIdentity().equals(process.rootCreationIdentity());
    }

    @Override
    public boolean owns(OwnedProcess process) {
        if (!bound(process)) {
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
            return refused(process, "the caller's window had already elapsed before anything was signalled");
        }
        if (!bound(process)) {
            // Not this controller's launch: no signal, no verdict, fail closed --
            // and say so, because a silent false proof is unoperable. A bound run
            // whose root is gone is deliberately NOT this path.
            log.warn("Run {} stop proof: binding mismatch for record {} (bound={}); nothing was signalled."
                            + " A bound run whose root is gone is a different path: it is stopped by identity,"
                            + " never refused here",
                    process.runId(), recordOf(process), boundIdentityOf(process.runId()));
            return new StopProof(process.runId(), false);
        }
        List<String> known = new ArrayList<>();
        mergeObserved(process.runId(), known);
        if (!known.contains(recordOf(process))) {
            known.add(recordOf(process));
        }
        try {
            String rootVerdict = verdictOf(recordOf(process));
            if (rootVerdict.startsWith(UNVERIFIABLE)) {
                // The root's identity is unknown: it may still be a live writer, and
                // no proof may be minted over a root that cannot be identified.
                return refused(process, "the root " + recordOf(process) + " cannot be verified ("
                        + rootVerdict + "); a root that may still be live is not stopped by identity");
            }
            // Only a live, identity-validated root may anchor the enumerated sweep.
            // A "mismatch" means the pid was recycled by an unrelated process, and a
            // "gone" root's orphaned children still carry its parent pid: the same
            // lookup cannot distinguish the two, so anchoring on either would
            // enumerate -- and potentially signal -- a stranger's children. Both take
            // the proof's identity-only path over the sampler's records and the job
            // inventory, with an empty sweep.
            boolean anchorTree = "match".equals(rootVerdict);
            if (!anchorTree) {
                // The run's core exited on its own (the WAITING_INPUT lifecycle) or its
                // pid was recycled: only the binding still identifies this run.
                log.info("Run {} stop proof: the root {} answered {} -- its binding still matches, but its"
                                + " tree cannot be distinguished from a recycled pid's tree and is not"
                                + " enumerated; stopping this run's observed records by identity",
                        process.runId(), recordOf(process), rootVerdict);
            }
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<String> live = anchorTree ? liveRecords(process) : List.of();
                List<String> gaps = live.stream().filter(WindowsProcessController::isTreeGap).toList();
                if (!gaps.isEmpty()) {
                    // F33 review P2: the walker dropped a subtree whose holder it could not
                    // identify. It was not signalled and its children were not followed, so the
                    // proof cannot know whether a live writer sits under it: it fails closed.
                    return refused(process, "the root's tree reports an unidentifiable descendant subtree "
                            + gaps + "; it cannot be certified as stopped, so nothing was signalled");
                }
                if (live.isEmpty()) {
                    break;
                }
                for (String record : live) {
                    if (!known.contains(record)) {
                        known.add(record);
                    }
                }
                operate(process, "kill", live);
                if (!Instant.now().isBefore(deadline)) {
                    return refused(process, "the caller's window elapsed during the root sweep");
                }
                Thread.sleep(SETTLE_MILLIS);
            }
            // Records discovered earlier can outlive the tree walk when an
            // intermediate process died: they are still killed by identity. The
            // verdicts are round trips too, so the caller's window is re-checked
            // inside the scan and before anything is signalled.
            //
            // R7 (review round 3, P3-d): the copy at the top of the proof is a
            // snapshot, and the root sweep above is a long series of round trips --
            // the sampler can add a record meanwhile (the set itself is weakly
            // consistent, so addAll on it is no guarantee either). A record added in
            // that window can be a live breakaway writer unreachable from the root's
            // tree and invisible to the job inventory: if the proof never scans it,
            // it certifies a live writer as stopped. The run's observed set is
            // therefore re-read here, after the sweep and before the straggler scan.
            Set<String> sampled = observedRecords.get(process.runId());
            if (sampled != null) {
                afterRootSweep.accept(sampled);
            }
            mergeObserved(process.runId(), known);
            List<String> stragglers = new ArrayList<>();
            for (String record : known) {
                if (!Instant.now().isBefore(deadline)) {
                    return refused(process, "the caller's window elapsed while the known records were"
                            + " verdict-checked; nothing further was signalled");
                }
                if ("match".equals(verdictOf(record))) {
                    stragglers.add(record);
                }
            }
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed before the straggler kill");
            }
            if (!stragglers.isEmpty()) {
                operate(process, "kill", stragglers);
                Thread.sleep(SETTLE_MILLIS);
            }
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed after the straggler kill");
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
                operate(process, "kill", ownJobSurvivors);
                Thread.sleep(SETTLE_MILLIS);
            }
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed after the job-survivor kill");
            }
            // R7 (review round 3, P3-d): the straggler kill and the inventory above are
            // round trips as well, so the population is re-read once more before the
            // per-record verdict decides the proof.
            mergeObserved(process.runId(), known);
            List<String> remaining = anchorTree ? liveRecords(process) : List.of();
            if (!remaining.isEmpty()) {
                log.warn("Run {} stop proof: records or unverifiable descendants remain in the root's tree {}; the proof fails closed",
                        process.runId(), remaining);
            }
            boolean clean = remaining.isEmpty();
            List<String> survivors = new ArrayList<>();
            List<String> unverifiable = new ArrayList<>();
            for (String record : known) {
                if (!Instant.now().isBefore(deadline)) {
                    return refused(process, "the caller's window elapsed while the final verdicts were decided");
                }
                // R7 (review round 3, P3-b): only a definite absence supports the proof.
                // A record the OS still verifies live survived the kills; a record it
                // cannot verify at all may be a live writer it cannot identify. Reading
                // either as "not match, therefore stopped" is exactly the fail-open
                // reading of an unreadable query.
                String verdict = verdictOf(record);
                if (verdict.startsWith(UNVERIFIABLE)) {
                    unverifiable.add(record + " (" + verdict + ")");
                } else if (!("gone".equals(verdict) || verdict.startsWith("mismatch"))) {
                    survivors.add(record + " (" + verdict + ")");
                }
            }
            if (!survivors.isEmpty()) {
                // A live record that survived every kill: the proof cannot be true over
                // it. Only OS identities are logged.
                log.warn("Run {} stop proof: live records remain after the kill sweep {}; the proof fails closed",
                        process.runId(), survivors);
            }
            if (!unverifiable.isEmpty()) {
                // Unknown is not absent: the record may be a live writer the OS cannot
                // identify, so it is neither signalled nor read as stopped.
                log.warn("Run {} stop proof: the OS could not verify records {}; an unverifiable record may"
                        + " still be a live writer, so the proof fails closed", process.runId(), unverifiable);
            }
            clean &= survivors.isEmpty() && unverifiable.isEmpty();
            Thread.sleep(SETTLE_MILLIS);
            List<String> stillLive = anchorTree ? liveRecords(process) : List.of();
            if (!stillLive.isEmpty()) {
                log.warn("Run {} stop proof: the root's tree still enumerates records or unverifiable descendants {} after the settle;"
                        + " the proof fails closed", process.runId(), stillLive);
            }
            clean &= stillLive.isEmpty();
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed before the final verdict");
            }
            // The job-membership completeness net (class javadoc): the enumerated tree
            // cannot see a descendant whose intermediate parent died before the first
            // enumeration, so the job inventory is the net that catches one. This run's
            // own members are covered by the checks above; another live run's members are
            // that run's writers -- left alone and excluded from this proof; anything
            // unaccounted for fails the proof closed. The inventory and its
            // classification are round trips like every earlier phase, so the caller's
            // window is re-checked around them as well: a proof is never returned after
            // the window elapsed.
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed before the job inventory");
            }
            List<String> job = jobMembers();
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed during the job inventory");
            }
            List<String> unaccounted = unaccountedJobMembers(process, known, job);
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed after the job inventory");
            }
            // F25: a descendant whose intermediate parent exited between two 500 ms tree
            // samples is unreachable from the root for the rest of the run, and the shared
            // job is the only place that can still observe it. The inventory cannot say
            // whose writer it is, so the run only claims a member when it is the single
            // supervised run and the member was created at or after its root -- then no
            // concurrent run exists to own it and it cannot predate the run. Attribution
            // never signals anything on its own: each attributed member is killed by
            // identity below and only leaves the unaccounted set when the OS reports it
            // gone or recycled, so a member that survives still fails the proof closed.
            // A launch that is still in flight counts as a supervised run here: its root is
            // already a job member of a live run this controller does not supervise yet, so
            // it is never this run's orphan.
            List<String> attributable = attributableJobMembers(unaccounted, rootCreationTicks(process),
                    supervisedOrLaunchingRunCount());
            if (!attributable.isEmpty()) {
                if (!Instant.now().isBefore(deadline)) {
                    return refused(process, "the caller's window elapsed before the attributed job members"
                            + " could be stopped");
                }
                log.info("Run {} stop proof: the shared job holds members {} created at or after this run's"
                                + " root while it is the only supervised run; stopping them by identity",
                        process.runId(), attributable);
                operate(process, "kill", attributable);
                Thread.sleep(SETTLE_MILLIS);
                List<String> attributedSurvivors = new ArrayList<>();
                for (String member : attributable) {
                    if (!Instant.now().isBefore(deadline)) {
                        return refused(process, "the caller's window elapsed while the attributed job"
                                + " members were verdict-checked");
                    }
                    // Only a definite absence or a recycled pid supports the attribution:
                    // a member the OS still verifies live, or cannot verify at all, stays
                    // unaccounted for and keeps the proof closed.
                    String verdict = verdictOf(member);
                    if ("gone".equals(verdict) || verdict.startsWith("mismatch")) {
                        unaccounted.remove(member);
                    } else {
                        attributedSurvivors.add(member + " (" + verdict + ")");
                    }
                }
                if (!attributedSurvivors.isEmpty()) {
                    log.warn("Run {} stop proof: attributed job members {} did not verify as stopped;"
                            + " the proof fails closed", process.runId(), attributedSurvivors);
                }
            }
            if (!unaccounted.isEmpty()) {
                // R7 diagnostics: without the inventory a failed proof is opaque. Only OS
                // identities (pid@creation) are logged -- never command lines, paths or
                // environment. The parenthetical states exactly what was enumerated: a
                // member of another supervised run whose own root no longer re-verifies is
                // not excluded above, so it can be neither attributed nor dismissed here.
                log.warn("Run {} stop proof: root={} known={} job={} unaccounted={} "
                                + "(not this run's records and not enumerated from another supervised root"
                                + " that still re-verifies); the proof fails closed",
                        process.runId(), verdictOf(recordOf(process)), known, job, unaccounted);
            }
            clean &= unaccounted.isEmpty();
            // F1 (review round 2): the inventory above was taken before the attribution
            // kill and its verdict round trips. A member being killed is still a live
            // process until the OS reports it gone, so it can spawn a child that inherits
            // the shared job after that inventory; the child is not in `unaccounted`, so a
            // proof that trusted the first inventory alone could return true while a live
            // job member keeps writing. The inventory is therefore repeated after the
            // attribution block, and any member still unaccounted fails the proof closed.
            // Nothing is signalled here: a member this proof never decided is not guessed
            // at. Only OS identities are logged.
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed before the second job inventory");
            }
            afterAttribution.run();
            List<String> jobAfter = jobMembers();
            if (!Instant.now().isBefore(deadline)) {
                return refused(process, "the caller's window elapsed during the second job inventory");
            }
            List<String> unaccountedAfter = unaccountedJobMembers(process, known, jobAfter);
            if (!unaccountedAfter.isEmpty()) {
                List<String> introduced = membersIntroducedSince(unaccountedAfter, unaccounted);
                List<String> persistent = unaccountedAfter.stream()
                        .filter(unaccounted::contains)
                        .toList();
                log.warn("Run {} stop proof: the second job inventory still holds unaccounted members {} "
                                + "(introduced after the first inventory={}, persistent from it={}); "
                                + "the proof fails closed",
                        process.runId(), unaccountedAfter, introduced, persistent);
            }
            clean &= unaccountedAfter.isEmpty();
            // R7 (review round 4, P2): the population re-read before the straggler scan is
            // not the proof's last word -- the per-record verdicts, the own-survivor kill
            // and the final inventory with its classification are round trips after it. A
            // record the sampler adds in that window was never scanned, killed or
            // verdict-checked, and a breakaway writer is invisible to the inventory, so
            // nothing else accounts for it: returning true would certify a live writer as
            // stopped. The proof therefore takes one last snapshot and fails closed unless
            // every record in it is one it already decided. It is a snapshot, not a merge:
            // deciding a late record inside this proof would add the round trips that
            // reopen exactly this window.
            Set<String> lastObserved = observedRecords.get(process.runId());
            if (lastObserved != null) {
                afterFinalInventory.accept(lastObserved);
                List<String> lateRecords = lastObserved.stream().filter(record -> !known.contains(record)).toList();
                if (!lateRecords.isEmpty()) {
                    // R7 diagnostics: only OS identities (pid@creation) are logged. The
                    // proof never probed these records, so their liveness is unknown and
                    // the proof is false regardless.
                    log.warn("Run {} stop proof: records first observed after the final inventory {};"
                            + " they were never scanned, killed or verdict-checked, so the proof fails closed",
                            process.runId(), lateRecords);
                }
                clean &= lateRecords.isEmpty();
            }
            return new StopProof(process.runId(), clean);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return refused(process, "the stop was interrupted before it could verify the writers");
        } catch (RuntimeException e) {
            // The channel refused or died mid-proof: nothing may be certified. The
            // message carries OS identities only (supervisor protocol lines).
            return refused(process, "the stop could not be completed: " + e.getMessage());
        }
    }

    /**
     * Fails one stop proof closed and logs why -- OS identities only, never a
     * command line, path or environment. Every false stop-proof path is logged:
     * this helper covers the deadline, unverifiable-root and later refusals, while
     * the binding-mismatch and final-clean paths log directly. The live
     * release-blocking failure returned a false proof with no diagnostic at all,
     * which left the operator unable to tell a binding mismatch from a root that
     * exited before its observed writers could be stopped.
     */
    private StopProof refused(OwnedProcess process, String path) {
        log.warn("Run {} stop proof refused: {}", process.runId(), path);
        return new StopProof(process.runId(), false);
    }

    /** The identity this controller bound for the run, for diagnostics only (never authority). */
    private String boundIdentityOf(UUID runId) {
        OwnedProcess known = started.get(runId);
        return known == null ? "none" : recordOf(known);
    }

    /**
     * Classifies every live member of the shared job against this run's proof. A
     * member that is one of this run's own discovered records was killed by
     * identity (its liveness is already part of the proof); a member of another
     * <em>live</em> run's enumerated tree is that run's writer and is excluded;
     * any other member is unaccounted for and fails the proof closed. Nothing
     * but this run's own records is ever signalled here.
     *
     * @return the unaccounted members (empty when the inventory is fully explained)
     */
    private List<String> unaccountedJobMembers(OwnedProcess process, List<String> ownRecords,
            List<String> jobMembers) {
        List<String> foreignRunTrees = new ArrayList<>();
        for (OwnedProcess other : started.values()) {
            if (!other.runId().equals(process.runId()) && rootMatches(other)) {
                foreignRunTrees.addAll(liveRecords(other));
            }
        }
        List<String> unaccounted = new ArrayList<>();
        for (String member : jobMembers) {
            if (ownRecords.contains(member) || foreignRunTrees.contains(member)) {
                continue;
            }
            unaccounted.add(member);
        }
        return unaccounted;
    }

    /**
     * F25: the shared-job members this proof may attribute to the run it is stopping.
     * A descendant whose intermediate parent died between two tree samples is
     * unreachable from the root's tree forever, and the shared job is the only place
     * that can still observe it; the inventory alone cannot say whose writer it is.
     * Attribution is therefore allowed only when this controller supervises exactly
     * one run <em>and</em> no launch is in flight -- no concurrent, foreign or
     * still-launching run exists that could own the member -- and the member's
     * creation ticks are not older than this run's root, because a process cannot be
     * created before the run that spawned it. The caller passes
     * {@code started.size() + inFlightLaunches.get()}, so a count of exactly one can
     * only mean that state. An {@code unverifiable:...} record names no identity at
     * all, a malformed record names no comparable creation time, and a member older
     * than the root predates the run, so none of those is ever attributed.
     *
     * <p>Nothing is signalled here: {@code stop} kills every attributed member by
     * identity and verdict-checks it, and the attribution only removes the member
     * from the unaccounted set once the OS reports it gone or recycled.
     *
     * @param unaccounted                   inventory members the proof could not classify
     * @param rootCreationTicks             the run root's creation identity in FILETIME ticks
     * @param supervisedOrLaunchingRunCount supervised runs plus launches still in flight;
     *                                      exactly one is required, so one run and no launch
     * @return the members attributable to this run (empty when ambiguous)
     */
    static List<String> attributableJobMembers(List<String> unaccounted, long rootCreationTicks,
            int supervisedOrLaunchingRunCount) {
        if (supervisedOrLaunchingRunCount != 1 || rootCreationTicks <= 0 || unaccounted == null
                || unaccounted.isEmpty()) {
            return List.of();
        }
        List<String> attributable = new ArrayList<>();
        for (String member : unaccounted) {
            // "unverifiable:<pid>" is an identity the OS could not read -- unknown is
            // never absence, and it is not even a pid@ticks record.
            if (member == null || member.startsWith(UNVERIFIABLE)) {
                continue;
            }
            int at = member.indexOf('@');
            if (at <= 0 || at != member.lastIndexOf('@') || at == member.length() - 1) {
                continue;
            }
            long pid;
            long created;
            try {
                pid = Long.parseLong(member.substring(0, at));
                created = Long.parseLong(member.substring(at + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid > 0 && created >= rootCreationTicks) {
                attributable.add(member);
            }
        }
        return attributable;
    }

    /**
     * F1: the members of {@code fresh} that {@code previous} did not hold -- the
     * identities that first appeared in a repeated job inventory. The comparison is
     * identity-only ({@code pid@creation}); command lines and paths are never part
     * of an inventory record. A null or empty {@code previous} accounts for nothing,
     * so every fresh member is introduced; a null or empty {@code fresh} introduces
     * nothing.
     */
    static List<String> membersIntroducedSince(List<String> fresh, List<String> previous) {
        if (fresh == null || fresh.isEmpty()) {
            return List.of();
        }
        List<String> introduced = new ArrayList<>();
        for (String member : fresh) {
            if (member == null || (previous != null && previous.contains(member))) {
                continue;
            }
            introduced.add(member);
        }
        return introduced;
    }

    /**
     * This run's root creation identity as FILETIME ticks, or a non-positive value
     * when it is not a readable tick count -- which attributes nothing.
     */
    private static long rootCreationTicks(OwnedProcess process) {
        try {
            return Long.parseLong(process.rootCreationIdentity());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * R7 (review round 3, P3-d): folds the run's current sampled population into the
     * proof's known records. The proof must call this after every phase that is a
     * long series of round trips -- the sampler adds records on its own thread, and a
     * writer attributed in those windows is otherwise never scanned, killed or
     * verdict-checked, which is a true proof over a live writer.
     */
    private void mergeObserved(UUID runId, List<String> known) {
        Set<String> observed = observedRecords.get(runId);
        if (observed == null) {
            return;
        }
        for (String record : observed) {
            if (!known.contains(record)) {
                known.add(record);
            }
        }
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
        observedRecords.remove(process.runId());
        observedPeaks.remove(process.runId());
        observedProbeCursors.remove(process.runId());
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
        // Terminal state first: the sampler, a concurrent stop or a later start must
        // never rebuild the channel that is being closed. Every run binding and every
        // sampled record belongs to the channel, so they go with it.
        closed = true;
        treeSampler.shutdownNow();
        started.clear();
        suspensions.clear();
        observedRecords.clear();
        observedPeaks.clear();
        observedProbeCursors.clear();
        synchronized (supervisorLock) {
            if (supervisor != null) {
                supervisor.close();
                supervisor = null;
            }
        }
        controlExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ sampling

    /** Test-visible state: the population the sampler currently remembers for one run. */
    int observedRecordCount(UUID runId) {
        Set<String> observed = observedRecords.get(runId);
        return observed == null ? 0 : observed.size();
    }

    /** Test-visible state: the largest population the sampler held for one run before pruning it. */
    int observedPeak(UUID runId) {
        return observedPeaks.getOrDefault(runId, 0);
    }

    /** Test-visible state: whether the sampler still remembers one identity for the run. */
    boolean observedRecordContains(UUID runId, String record) {
        Set<String> observed = observedRecords.get(runId);
        return observed != null && observed.contains(record);
    }

    /**
     * F2: the number of runs whose writers a stop proof must treat as claimed -- the
     * registered runs plus the launches still in flight. A launch is in flight from
     * before the OS can make it a job member until it is registered, so a value of
     * exactly {@code 1} means one supervised run and no concurrent launch. That is
     * the only state in which attribution may claim a job member.
     */
    int supervisedOrLaunchingRunCount() {
        return started.size() + inFlightLaunches.get();
    }

    /**
     * Test-visible: the shared job's current membership, exactly as the stop proof's
     * inventory reads it ({@code pid@creation}, OS identities only). It lets a test
     * assert that a fixture writer really is a job member instead of assuming it: a
     * writer outside the job would survive the job-attributed kill and make the
     * premise vacuous.
     */
    List<String> jobMemberRecords() {
        return jobMembers();
    }

    /**
     * Test-visible seam: forces the supervision channel through {@link #supervisor()}
     * so the terminal-state guard on channel creation is asserted directly -- the
     * binding look-up in {@code owns()}/{@code stop()} returns before it otherwise.
     */
    String supervisorSelfIdentity() {
        return supervisor().selfIdentity();
    }

    /**
     * Test-visible: one raw exchange on the real supervision channel. The embedded
     * script's identity classification only executes on Windows, so its protocol
     * test drives the same PowerShell process, script and line protocol every
     * control action uses -- a revert of the classification in {@link #SCRIPT}
     * fails a test instead of silently restoring the fail-open reading.
     */
    String supervisorCommand(String line) {
        return supervisor().command(line);
    }

    /** Test-visible: the pid of the live supervision channel (starts it if needed). */
    long supervisorPid() {
        return supervisor().process.pid();
    }

    /**
     * Starts the one sampler this controller ever runs (latched): the tree of every
     * supervised run is sampled while it is live.
     */
    private void startSampling() {
        if (sampling) {
            return;
        }
        synchronized (supervisorLock) {
            if (sampling) {
                return;
            }
            sampling = true;
        }
        treeSampler.scheduleWithFixedDelay(this::sampleLiveTrees, 500, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * R7: samples every live run's enumerated tree so a descendant that outlives its
     * root stays attributable at stop time. The sampler signals nothing and decides
     * no proof; it only remembers identity-validated records. A supervisor hiccup
     * skips one sample -- the stop proof still fails closed on its own.
     *
     * <p>The channel is a non-creating snapshot: the sampler never builds a
     * supervisor. One created here after {@code release()}/{@code close()} would be
     * closed by nobody, and {@code new Supervisor()} can block for the whole start
     * window inside the supervisor lock, stalling {@code start()}/{@code release()}.
     */
    private void sampleLiveTrees() {
        if (closed || started.isEmpty()) {
            return; // closed or between runs: there is nothing to sample
        }
        Supervisor current = liveSupervisor();
        if (current == null) {
            return; // no live channel: a sample must never rebuild one
        }
        for (Map.Entry<UUID, OwnedProcess> entry : started.entrySet()) {
            Set<String> observed = observedRecords.get(entry.getKey());
            if (observed == null) {
                continue;
            }
            try {
                OwnedProcess process = entry.getValue();
                if (!"match".equals(current.command("record " + recordOf(process)))) {
                    continue;
                }
                List<String> sampled = descendantsCreatedAtOrAfterRoot(
                        recordsFrom(current.command("tree " + process.rootPid())),
                        process.rootPid(), process.rootCreationIdentity());
                for (String record : sampled) {
                    // A gap names no identity: the sampler remembers identities only, and the
                    // stop proof's own read reports the gap and fails closed.
                    if (!isTreeGap(record)) {
                        observed.add(record);
                    }
                }
                observedPeaks.merge(entry.getKey(), observed.size(), Math::max);
                pruneObserved(entry.getKey(), current, observed);
            } catch (RuntimeException e) {
                log.debug("Run {} descendant sampling skipped: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * The live supervision channel, or null when there is none. Never creates one:
     * the sampler must not race {@code release()}/{@code close()} into building a
     * channel nobody owns.
     */
    private Supervisor liveSupervisor() {
        synchronized (supervisorLock) {
            return supervisor != null && supervisor.isAlive() ? supervisor : null;
        }
    }

    /**
     * R7 memory bound ({@link #MAX_OBSERVED_RECORDS}): a gone or recycled identity
     * can never account for a live writer, so an over-cap population spends a
     * bounded number of probes on records the OS reports gone or recycled and
     * evicts only those. The cap is deliberately <b>soft</b>: a record the OS still
     * reports live -- and a record it cannot verify at all -- is never shed. In the
     * documented best-effort job configuration (the assignment refused with
     * {@code assign_failed:5}, breakaway children invisible to the job inventory)
     * the sampled record is the only attribution evidence for a child unreachable
     * from the root at stop time, so shedding it would let the proof miss a live
     * writer and return {@code allWritersStopped=true} over it. An over-cap live
     * population is therefore retained: the cap bounds dead evidence only.
     *
     * <p>R7 (review round 3, P3-a): the probe budget is per pass, and each pass
     * starts at the run's cursor instead of at the head of the iteration order.
     * Without the rotation, 32+ live records at the head would consume the whole
     * budget on every pass and the gone records behind them would never be probed --
     * the population would never be pruned at all, the opposite of a memory bound.
     * The cursor advances past the last probed record and wraps, so consecutive
     * passes sweep the whole population. Nothing here signals anything.
     */
    private void pruneObserved(UUID runId, Supervisor current, Set<String> observed) {
        if (observed.size() <= MAX_OBSERVED_RECORDS) {
            return;
        }
        List<String> population = new ArrayList<>(observed);
        int size = population.size();
        if (size == 0) {
            return;
        }
        int cursor = Math.floorMod(observedProbeCursors.getOrDefault(runId, 0), size);
        int probes = 0;
        while (probes < OBSERVED_PRUNE_PROBES && probes < size && observed.size() > MAX_OBSERVED_RECORDS) {
            String record = population.get(cursor);
            cursor = (cursor + 1) % size;
            probes++;
            String verdict;
            try {
                verdict = current.command("record " + record);
            } catch (RuntimeException e) {
                break; // a channel hiccup ends this pass; the next pass resumes at the cursor
            }
            if ("gone".equals(verdict) || verdict.startsWith("mismatch")) {
                observed.remove(record);
            }
            // R7 (review round 3, P3-b): an unverifiable (or otherwise unrecognised)
            // verdict is not a definite absence -- the record stays as evidence.
        }
        observedProbeCursors.put(runId, cursor);
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
                List<String> live = liveRecords(process);
                List<String> gaps = live.stream().filter(WindowsProcessController::isTreeGap).toList();
                if (!gaps.isEmpty()) {
                    // F33 review P2: an unidentifiable subtree is not a paused subtree. The
                    // supervisor never signals a gap; the pause therefore cannot be verified.
                    log.warn("Run {} pause: the root's tree reports an unidentifiable descendant subtree {};"
                            + " the pause is not verified", process.runId(), gaps);
                    return new ControlAck(ControlState.RUNNING, false);
                }
                List<String> fresh = live.stream()
                        .filter(record -> !ledger.containsKey(record))
                        .toList();
                if (fresh.isEmpty()) {
                    break;
                }
                Map<String, String> results = operate(process, "suspend", fresh);
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
                    && liveRecords(process).stream().allMatch(ledger::containsKey);
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
                String applied = operate(process, "resume", List.of(entry.getKey())).get(entry.getKey());
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
            if (closed) {
                throw new IllegalStateException("This Host process controller is closed;"
                        + " no supervision channel can be created");
            }
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
    private List<String> liveRecords(OwnedProcess process) {
        return descendantsCreatedAtOrAfterRoot(recordsFrom(command("tree " + process.rootPid())),
                process.rootPid(), process.rootCreationIdentity());
    }

    /**
     * F33: the tree's root boundary. A process can never be a child of a process
     * created after it, so a record older than the run's root cannot be one of its
     * writers. The supervisor already refuses to follow a stale parent link whose
     * child predates its parent; this filter is the Java backstop that keeps such a
     * record out of every read built on the tree even if the walker regresses. It
     * also anchors the read on the identity the controller verified: a non-empty
     * tree must contain the root pid carrying exactly the recorded creation
     * identity, or the root died and its pid was reused between the anchor verdict
     * and the enumeration -- an unanchored walk of the stranger's tree would
     * otherwise pass every filter. The root itself sits exactly at the boundary and
     * stays listed. A malformed record or an unreadable root identity fails the read
     * closed: neither can be shown to be a descendant, and neither may be signalled
     * on a guess. An {@code unverifiable:<pid>} gap is carried through unchanged --
     * it is proof evidence, never a killable identity.
     */
    static List<String> descendantsCreatedAtOrAfterRoot(List<String> records, long rootPid,
            String rootCreationIdentity) {
        long recorded;
        try {
            recorded = Long.parseLong(rootCreationIdentity);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("a descendant filter needs the run root's creation ticks", e);
        }
        if (recorded <= 0) {
            throw new IllegalArgumentException("a descendant filter needs the run root's creation ticks");
        }
        long floor = recorded - CREATION_ORDER_TOLERANCE_TICKS;
        List<String> kept = new ArrayList<>(records.size());
        boolean anchored = false;
        for (String record : records) {
            if (record == null) {
                throw new IllegalStateException("Malformed tree record: null");
            }
            if (isTreeGap(record)) {
                kept.add(record);
                continue;
            }
            int at = record.lastIndexOf('@');
            if (at <= 0 || at == record.length() - 1) {
                throw new IllegalStateException("Malformed tree record: " + record);
            }
            long pid;
            long created;
            try {
                pid = Long.parseLong(record.substring(0, at));
                created = Long.parseLong(record.substring(at + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Malformed tree record: " + record, e);
            }
            if (pid == rootPid) {
                if (Math.abs(created - recorded) > CREATION_ORDER_TOLERANCE_TICKS) {
                    throw new IllegalStateException("the tree root " + pid + " carries a different creation"
                            + " identity than the run's recorded root; the pid was reused between the"
                            + " ownership verdict and the enumeration");
                }
                anchored = true;
            }
            if (created >= floor) {
                kept.add(record);
            }
        }
        if (!records.isEmpty() && !anchored) {
            throw new IllegalStateException("the tree does not contain the run root " + rootPid
                    + "; it cannot be this run's tree");
        }
        return kept;
    }

    /** A tree read's marker for a subtree the OS could not identify (F33). */
    static boolean isTreeGap(String record) {
        return record != null && record.startsWith(UNVERIFIABLE + ":");
    }

    /**
     * Test-visible: parses a supervisor tree payload ("pid@created@depth;..." plus
     * "unverifiable:pid@depth" gaps) into records, deepest first.
     */
    static List<String> recordsFrom(String payload) {
        List<Map.Entry<String, Integer>> records = new ArrayList<>();
        for (String entry : payload.split(";", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split("@", -1);
            if (parts.length > 0 && parts[0].startsWith(UNVERIFIABLE + ":")) {
                // "unverifiable:<pid>@<depth>" from the command handler; the raw walker
                // form also carries the parent ticks ("unverifiable:<pid>@<parentTicks>@<depth>").
                if (parts.length == 2) {
                    records.add(Map.entry(parts[0], Integer.parseInt(parts[1])));
                    continue;
                }
                if (parts.length == 3) {
                    records.add(Map.entry(parts[0], Integer.parseInt(parts[2])));
                    continue;
                }
                throw new IllegalStateException("Malformed supervisor tree entry: " + entry);
            }
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

    /**
     * Runs one record batch on the supervision channel. Every mutating operation is
     * guarded (F33): this controller's own JVM and its supervision channel are never
     * signalled -- killing either strands the proof that asked for it and every later
     * control action -- and a record created before the run's root cannot be one of
     * its writers. A record that is not a well-formed {@code pid@positiveTicks} is
     * refused too: a bare pid would otherwise match in {@code Test-Record} and be
     * signalled. A refused record is reported as {@code refused:*}, never applied;
     * because it stays live, the caller's verdict loop fails the proof closed over it
     * rather than certifying a writer that was not stopped. A root identity that
     * cannot be read fails the whole batch before anything is sent.
     */
    private Map<String, String> operate(OwnedProcess process, String action, List<String> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        long rootTicks = rootCreationTicks(process);
        if (rootTicks <= 0) {
            throw new IllegalStateException("the run root's creation identity is not readable;"
                    + " no control batch is safe");
        }
        long controllerPid = ProcessHandle.current().pid();
        long supervisorPid = supervisorPid();
        List<String> send = new ArrayList<>(records.size());
        Map<String, String> results = new LinkedHashMap<>();
        for (String record : records) {
            String refusal = refusalOf(record, controllerPid, supervisorPid, rootTicks);
            if (refusal != null) {
                results.put(record, refusal);
                log.warn("Run {} control {} refused for record {} ({}); nothing was signalled",
                        process.runId(), action, record, refusal);
            } else {
                send.add(record);
            }
        }
        if (send.isEmpty()) {
            return results;
        }
        String payload = command(action + " " + String.join(";", send));
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

    /**
     * Why a mutating operation must never reach this record, or {@code null} when it
     * may. The controller pid guard is also the Java half of the supervisor's own
     * self-kill refusal: the supervisor protects itself, the backend JVM can only be
     * protected here. Anything that is not a well-formed {@code pid@positiveTicks}
     * is refused rather than sent: {@code Test-Record} treats a bare pid as a match,
     * so a malformed record would otherwise be signalled. The caller guarantees a
     * readable root identity ({@link #operate} throws otherwise); a non-positive
     * value disables only the predates-run classification for this pure helper.
     */
    static String refusalOf(String record, long controllerPid, long supervisorPid, long rootCreationTicks) {
        if (record == null || isTreeGap(record)) {
            return "refused:malformed";
        }
        int at = record.indexOf('@');
        if (at <= 0 || at != record.lastIndexOf('@') || at == record.length() - 1) {
            return "refused:malformed";
        }
        long pid;
        try {
            pid = Long.parseLong(record.substring(0, at));
        } catch (NumberFormatException e) {
            return "refused:malformed";
        }
        if (pid <= 0) {
            return "refused:malformed";
        }
        // The identity guards precede the creation-time validation: a well-formed
        // record that names this controller or its channel is refused for that reason
        // even if its ticks are unusable.
        if (pid == controllerPid) {
            return "refused:controller";
        }
        if (pid == supervisorPid) {
            return "refused:supervisor";
        }
        long created;
        try {
            created = Long.parseLong(record.substring(at + 1));
        } catch (NumberFormatException e) {
            return "refused:malformed";
        }
        if (created <= 0) {
            return "refused:malformed";
        }
        if (rootCreationTicks > 0 && created < rootCreationTicks - CREATION_ORDER_TOLERANCE_TICKS) {
            return "refused:predates-run";
        }
        return null;
    }

    /** Test-visible: one guarded kill batch through the same path the stop proof uses. */
    Map<String, String> killRecords(OwnedProcess process, List<String> records) {
        return operate(process, "kill", records);
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
              // The creation identity of a live PID: FILETIME ticks since 1601 (100 ns). The three
              // outcomes are deliberately distinct: "<ticks>" when the OS verified the identity, "gone"
              // only for a definite absence, and "unverifiable:<detail>" when the query failed for any
              // other reason. Only ERROR_INVALID_PARAMETER (87) from OpenProcess means "no such
              // process", and a process that exited reports an exit code other than STILL_ACTIVE (a
              // terminated process with an open handle -- every ProcessBuilder child whose Process
              // object is still referenced -- keeps answering GetProcessTimes, so liveness is decided
              // by the exit code and never by the mere presence of the PID). Any other failure leaves
              // the identity unknown, and unknown must never be read as absence: a record shed that way
              // is a live writer the stop proof would then certify as stopped. A protected process
              // (System, csrss, lsass, ...) answers access denied (5), which says nothing about the
              // recorded process: its pid may have been recycled by the protected process, or it may
              // be the protected process itself. Test-Record tells those apart with a handle-free WMI
              // creation-time probe (F32), never by guessing.
              public static string Identity(int pid) {
                IntPtr hp = OpenProcess(QueryLimited, false, pid);
                if (hp == IntPtr.Zero) {
                  int error = Marshal.GetLastWin32Error();
                  return error == 87 ? "gone" : "unverifiable:open:" + error;
                }
                try {
                  uint exitCode;
                  if (!GetExitCodeProcess(hp, out exitCode)) {
                    return "unverifiable:exit-code:" + Marshal.GetLastWin32Error();
                  }
                  if (exitCode != StillActive) { return "gone"; }
                  FILETIME creation, exit, kernel, user;
                  if (!GetProcessTimes(hp, out creation, out exit, out kernel, out user)) {
                    return "unverifiable:times:" + Marshal.GetLastWin32Error();
                  }
                  return ((long)(((ulong)creation.High << 32) | creation.Low)).ToString();
                } finally { CloseHandle(hp); }
              }
              // The creation identity of a verifiable PID, or null when it is not: the enumeration
              // paths (tree, self record) list only identities the OS confirmed, and the stop proof
              // treats an unlisted process as unaccounted rather than absent.
              public static string Creation(int pid) {
                string identity = Identity(pid);
                return identity == "gone" || identity.StartsWith("unverifiable") ? null : identity;
              }
              // 10 ms in FILETIME ticks (the same bound Test-Record uses). Creation order is
              // exact at kernel resolution; the F32 measurement of the WMI/kernel delta was under
              // 1 ms, so this is a wide margin against an unforeseen clock-source disagreement and
              // never a real pid reuse (the live F33 reuse was 12 minutes). The Java
              // CREATION_ORDER_TOLERANCE_TICKS carries the same value and the "link" protocol test
              // pins the two together.
              const long CreationOrderTolerance = 100000L;
              public static bool IsCreationOrderLink(long childCreated, long parentCreated) {
                return childCreated >= parentCreated - CreationOrderTolerance;
              }
              // Identity-validated descendant tree of one root: "pid@created@depth" entries, plus
              // "unverifiable:<pid>@<depth>" for a subtree the OS cannot identify.
              // th32ParentProcessID is the creator's pid at creation time, and a dead creator's
              // pid may be reused by a newer, unrelated process (F33): the link then points at a
              // stranger that predates the process it claims to have created. A process can never
              // be a child of a process created after it, so a link whose child predates its
              // parent is stale and its whole subtree is not this root's -- the live failure had
              // the backend and its supervisor (12 minutes older than the run root) adopted this
              // way and killed by the stop proof. A gone node's children are not followed either:
              // an earlier holder of its pid may have created some of them (they are not ours),
              // and with the node gone the link cannot be validated. A live but unreadable holder
              // (protected/elevated) is reported as an "unverifiable:<pid>@<parentTicks>@<depth>"
              // gap: the command handler applies the F32 handle-free creation probe and drops a
              // stale link whose holder predates the parent, while a genuine child it cannot open
              // stays a gap so the proof fails closed instead of silently omitting the subtree.
              // The root itself must be identity-verified, or the tree is empty.
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
                  string rootIdentity = Identity(root);
                  long rootTicks;
                  if (rootIdentity == "gone" || rootIdentity.StartsWith("unverifiable")
                      || !long.TryParse(rootIdentity, out rootTicks)) {
                    return records.ToArray();
                  }
                  records.Add(root + "@" + rootIdentity + "@0");
                  var queue = new Queue<long[]>();
                  var seen = new HashSet<int>();
                  seen.Add(root);
                  List<int> direct;
                  if (children.TryGetValue(root, out direct)) {
                    foreach (int child in direct) { queue.Enqueue(new long[] { child, 1, rootTicks }); }
                  }
                  while (queue.Count > 0) {
                    long[] node = queue.Dequeue();
                    int pid = (int)node[0];
                    int depth = (int)node[1];
                    if (!seen.Add(pid)) { continue; }
                    string identity = Identity(pid);
                    if (identity == "gone") { continue; }
                    if (identity.StartsWith("unverifiable")) {
                      // The parent ticks travel with the gap: the command handler classifies it
                      // with the F32 handle-free probe (older than the parent = stale link to a
                      // stranger, newer = a genuine child we cannot open).
                      records.Add("unverifiable:" + pid + "@" + node[2] + "@" + depth);
                      continue;
                    }
                    long createdTicks;
                    if (!long.TryParse(identity, out createdTicks)) { continue; }
                    if (!IsCreationOrderLink(createdTicks, node[2])) { continue; }
                    records.Add(pid + "@" + identity + "@" + depth);
                    List<int> list;
                    if (children.TryGetValue(pid, out list)) {
                      foreach (int child in list) {
                        queue.Enqueue(new long[] { child, depth + 1, createdTicks });
                      }
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
            function Get-Identity([int]$targetPid) {
              return [AriaHostControl]::Identity($targetPid)
            }
            function Get-RecordOf([int]$targetPid) {
              $identity = Get-Identity $targetPid
              if ($identity -eq 'gone' -or $identity.StartsWith('unverifiable')) { return $null }
              return ('{0}@{1}' -f $targetPid, $identity)
            }
            # Handle-free creation time of the process now holding a pid (F32). WMI exposes
            # CreationDate for protected processes, whose OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)
            # is refused with access denied (5). The measured WMI/kernel creation-time difference is
            # far below a millisecond. The three outcomes are deliberate: "<ticks>" when WMI read the
            # identity, "absent" only when WMI lists no process holding the pid, and "error" when the
            # query failed or the listed instance carries no readable creation time -- unknown is never
            # absence. The operation timeout bounds the query where the provider honours the session
            # timeout; a hung command is bounded by the supervisor's 120 s command wait, with the
            # caller's deadline checked between round trips.
            function Get-CreationTicksWithoutHandle([int]$targetPid) {
              try {
                $p = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $targetPid" -OperationTimeoutSec 5 -ErrorAction Stop
                if ($null -eq $p) { return 'absent' }
                if ($null -eq $p.CreationDate) { return 'error' }
                return $p.CreationDate.ToUniversalTime().ToFileTimeUtc().ToString()
              } catch { return 'error' }
            }
            function Test-Record([string]$record) {
              $parts = $record.Split('@')
              $targetPid = [int]$parts[0]
              $expected = if ($parts.Count -gt 1) { $parts[1] } else { '' }
              $identity = Get-Identity $targetPid
              if ($identity -eq 'gone') { return 'gone' }
              if ($identity.StartsWith('unverifiable')) {
                # Unknown is not absence, but a protected process makes the refusal ambiguous: the
                # pid may now be held by System/csrss/lsass (the recorded process is gone) or the
                # record may name that protected process itself (a live writer we cannot open). Only
                # a definite, handle-free creation time decides; a failed probe or a malformed
                # expected identity keeps the original unverifiable verdict. The probe is used only
                # on this branch and is deliberately not cached.
                $expectedTicks = 0L
                # A record names exactly pid@ticks with a positive creation identity. Anything else
                # is malformed and keeps the original verdict instead of being reclassified.
                if ($parts.Count -ne 2) { return $identity }
                if (-not [long]::TryParse($expected, [ref]$expectedTicks) -or $expectedTicks -le 0L) { return $identity }
                $probe = Get-CreationTicksWithoutHandle $targetPid
                if ($probe -eq 'absent') { return 'gone' }
                if ($probe -eq 'error') { return $identity }
                # Compare local wall-clock times, not UTC: ToUniversalTime() resolves the DST
                # fall-back repeated hour with the standard offset and could shift a same-process read
                # by an hour, which would turn a fail-closed verdict into a false mismatch. The
                # wall-clock form can only err toward "same": in the repeated hour it collapses two
                # UTC instants one hour apart, so a recycled pid created exactly one hour after the
                # record can stay unverifiable (a one-hour-per-year false red, never a false clean).
                # The 10 ms tolerance is over 10x the fixture's millisecond truncation and far above
                # the measured WMI/kernel difference; only a helper that lived under 10 ms can remain
                # a false red.
                $observedLocal = [DateTime]::FromFileTimeUtc([long]$probe).ToLocalTime()
                $expectedLocal = [DateTime]::FromFileTimeUtc($expectedTicks).ToLocalTime()
                if ([Math]::Abs($observedLocal.Ticks - $expectedLocal.Ticks) -le 100000L) { return $identity }
                return ('mismatch:' + $targetPid + '@' + $probe)
              }
              if ($expected -eq '' -or $identity -eq $expected) { return 'match' }
              return ('mismatch:' + $targetPid + '@' + $identity)
            }
            function Invoke-Records([string]$records, [int]$op) {
              $results = @()
              foreach ($record in ($records.Split(';') | Where-Object { $_ -ne '' })) {
                $verdict = Test-Record $record
                if ($verdict -ne 'match') { $results += ('{0}:{1}' -f $record, $verdict); continue }
                $targetPid = [int]($record.Split('@')[0])
                # F33: the supervision channel never signals itself. It is the only way to
                # stop the run, so a self-kill strands the proof that asked for it and every
                # later control action. The record may have reached the batch through a stale
                # parent-pid link; refuse it and report it, never apply it.
                if ($targetPid -eq $PID) { $results += ('{0}:{1}' -f $record, 'refused:self'); continue }
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
                      $identity = Get-Identity ([int]$member)
                      if ($identity -eq 'gone') { continue }
                      if ($identity.StartsWith('unverifiable')) {
                        # An identity the OS cannot read is not an absence: it stays in the inventory
                        # as an unaccounted member, which fails the stop proof closed.
                        $recs += ('unverifiable:' + $member)
                        continue
                      }
                      $recs += ('{0}@{1}' -f $member, $identity)
                    }
                    $r = 'ok|' + ($recs -join ';')
                  }
                  'identity' { $rec = Get-RecordOf ([int]$arg); if ($null -eq $rec) { $r = 'ok|gone' } else { $r = 'ok|' + $rec } }
                  'record'   { $r = 'ok|' + (Test-Record $arg) }
                  # Test-visible classifier of one parent link: the same function Tree applies.
                  'link'     {
                    $pair = $arg.Split(' ', 2)
                    $r = 'ok|' + $(if ([AriaHostControl]::IsCreationOrderLink([long]$pair[0], [long]$pair[1])) { 'descendant' } else { 'stale' })
                  }
                  'tree'     {
                    $raw = [AriaHostControl]::Tree([int]$arg)
                    $kept = @()
                    foreach ($entry in $raw) {
                      if ($entry.StartsWith('unverifiable:')) {
                        # F33: a node the OS would not let us open is either a genuine child of
                        # the parent (a live writer we cannot kill) or a stale parent link to a
                        # stranger older than it. The F32 handle-free probe decides; only an
                        # older holder is dropped, and a failed probe keeps the gap.
                        $gap = $entry.Split('@')
                        $gapPid = [int]$gap[0].Substring('unverifiable:'.Length)
                        $probe = Get-CreationTicksWithoutHandle $gapPid
                        if ($probe -eq 'absent') { continue }
                        if ($probe -ne 'error') {
                          if (-not [AriaHostControl]::IsCreationOrderLink([long]$probe, [long]$gap[1])) { continue }
                        }
                        $kept += ('unverifiable:' + $gapPid + '@' + $gap[2])
                        continue
                      }
                      $kept += $entry
                    }
                    $r = 'ok|' + ($kept -join ';')
                  }
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
