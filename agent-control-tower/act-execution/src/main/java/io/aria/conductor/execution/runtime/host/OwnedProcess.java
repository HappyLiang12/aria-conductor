package io.aria.conductor.execution.runtime.host;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable ownership record of one Host run's owned runtime (spec 5.3): the root
 * PID <em>together with</em> its OS creation identity and the run's ownership
 * nonce, plus the identity of the OS supervision authority that attached to the
 * run and the technique that authority verified.
 *
 * <p>A PID alone is not an ownership identity -- it is recycled by the OS and
 * every control action would then risk acting on an unrelated process. All
 * control paths re-verify {@link #rootPid()} against
 * {@link #rootCreationIdentity()} through the OS before touching anything, and
 * the ownership nonce binds the record to the launch that produced it: a
 * controller acts on a record only after matching its nonce, pid and creation
 * identity against the in-memory binding it created for that run at
 * {@code start}, and only after the OS still confirms the creation identity.
 * The nonce is therefore verified by the launching controller alone; it is
 * never a store the record carries on its own authority.
 *
 * <p>{@link #liveProcess} is the launching JVM's live handle for stream access
 * (a stdio-transport core) and unexpected-exit observation. It is memory-only:
 * a record reconstructed from {@link #ownershipIdentity()} (after a backend
 * restart) carries {@code null}. Such a record authorizes <em>nothing</em> --
 * the controller that could verify its nonce is gone, so every control path
 * refuses it (fail closed) rather than acting on a pid plus creation identity
 * alone. Adopting or reaping a run that survived a restart is the recovery
 * coordinator's job, with run-store evidence.
 */
public record OwnedProcess(UUID runId, String ownershipNonce, long rootPid,
        String rootCreationIdentity, String supervisorIdentity, String technique,
        Process liveProcess) {

    /** Marker prefix of {@link #ownershipIdentity()}. */
    public static final String IDENTITY_KIND = "host";

    private static final String SEPARATOR = "|";
    private static final int ENCODED_FIELDS = 6;

    public OwnedProcess {
        Objects.requireNonNull(runId, "runId");
        requireText(ownershipNonce, "ownershipNonce");
        if (rootPid <= 0) {
            throw new IllegalArgumentException("rootPid must be a live process id, got " + rootPid);
        }
        requireText(rootCreationIdentity, "rootCreationIdentity");
        requireText(supervisorIdentity, "supervisorIdentity");
        requireText(technique, "technique");
        if (supervisorIdentity.indexOf(SEPARATOR) >= 0 || technique.indexOf(SEPARATOR) >= 0
                || ownershipNonce.indexOf(SEPARATOR) >= 0 || rootCreationIdentity.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("Ownership identity fields must not contain '" + SEPARATOR + "'");
        }
    }

    /** The durable identity carried by a {@code RuntimeHandle}. */
    public String ownershipIdentity() {
        return ownershipIdentityOf(this);
    }

    /** The durable identity of one ownership record, exactly as a handle carries it. */
    public static String ownershipIdentityOf(OwnedProcess process) {
        Objects.requireNonNull(process, "process");
        return String.join(SEPARATOR, IDENTITY_KIND, process.runId().toString(), process.ownershipNonce(),
                process.rootPid() + "@" + process.rootCreationIdentity(),
                process.supervisorIdentity(), process.technique());
    }

    /**
     * Reconstructs a record from a persisted ownership identity. A malformed or
     * foreign identity is rejected explicitly instead of degrading to a PID
     * guess; the returned record carries no live process handle and no binding
     * to a launch, so it authorizes no control action (see the class contract).
     */
    public static OwnedProcess parse(String ownershipIdentity) {
        Objects.requireNonNull(ownershipIdentity, "ownershipIdentity");
        String[] fields = ownershipIdentity.split("\\" + SEPARATOR, -1);
        if (fields.length != ENCODED_FIELDS || !IDENTITY_KIND.equals(fields[0])) {
            throw new IllegalArgumentException("Not a Host ownership identity: " + ownershipIdentity);
        }
        UUID runId;
        try {
            runId = UUID.fromString(fields[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed run id in ownership identity: " + ownershipIdentity);
        }
        String nonce = fields[2];
        int at = fields[3].indexOf('@');
        if (at <= 0 || at == fields[3].length() - 1) {
            throw new IllegalArgumentException("Malformed root record in ownership identity: " + ownershipIdentity);
        }
        long rootPid;
        try {
            rootPid = Long.parseLong(fields[3].substring(0, at));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed root pid in ownership identity: " + ownershipIdentity);
        }
        return new OwnedProcess(runId, nonce, rootPid, fields[3].substring(at + 1),
                fields[4], fields[5], null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
