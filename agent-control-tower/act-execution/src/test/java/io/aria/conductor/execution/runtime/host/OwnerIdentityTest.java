package io.aria.conductor.execution.runtime.host;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage of the durable ownership identity (Task 9 review round 1
 * restored it): the encoded record carries the run id, the ownership nonce, the
 * pid <em>with</em> its creation identity, the supervisor identity and the
 * technique -- and a record reconstructed from it never carries a process
 * handle. Malformed or foreign identities are rejected explicitly, never
 * degraded to a PID guess.
 */
class OwnerIdentityTest {

    private static final String TECHNIQUE =
            "identity-validated descendant-tree enumeration (pid + start time) + SIGSTOP/SIGCONT/SIGKILL";

    @Test
    void ownershipIdentityRoundTripsEveryDurableField() {
        UUID runId = UUID.randomUUID();
        OwnedProcess owned = new OwnedProcess(runId, "nonce-1", 4711L, "4711@1700000000000",
                "9000@1700000000000", TECHNIQUE, null);

        String identity = owned.ownershipIdentity();
        assertThat(identity).startsWith(OwnedProcess.IDENTITY_KIND + "|");

        OwnedProcess parsed = OwnedProcess.parse(identity);
        assertThat(parsed.runId()).isEqualTo(runId);
        assertThat(parsed.ownershipNonce()).isEqualTo("nonce-1");
        assertThat(parsed.rootPid()).isEqualTo(4711L);
        assertThat(parsed.rootCreationIdentity()).isEqualTo("4711@1700000000000");
        assertThat(parsed.supervisorIdentity()).isEqualTo("9000@1700000000000");
        assertThat(parsed.technique()).isEqualTo(TECHNIQUE);
        assertThat(parsed.liveProcess()).as("a reconstructed record has no live process handle").isNull();
        assertThat(OwnedProcess.parse(identity)).isEqualTo(parsed);
        assertThat(OwnedProcess.ownershipIdentityOf(owned)).isEqualTo(identity);
    }

    @Test
    void parseRejectsMalformedOrForeignIdentities() {
        String runId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> OwnedProcess.parse("sandbox|" + runId + "|nonce|1@1|2@2|t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a Host ownership identity");
        assertThatThrownBy(() -> OwnedProcess.parse("host|" + runId + "|nonce|1@1|2@2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a Host ownership identity");
        assertThatThrownBy(() -> OwnedProcess.parse("host|not-a-uuid|nonce|1@1|2@2|t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run id");
        assertThatThrownBy(() -> OwnedProcess.parse("host|" + runId + "|nonce|4711@|2@2|t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root record");
        assertThatThrownBy(() -> OwnedProcess.parse("host|" + runId + "|nonce|4711|2@2|t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root record");
        assertThatThrownBy(() -> OwnedProcess.parse("host|" + runId + "|nonce|no-pid@1|2@2|t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root pid");
    }

    @Test
    void aRecordRequiresItsDurableIdentityFields() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> new OwnedProcess(runId, " ", 4711L, "4711@1", "2@2", TECHNIQUE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownershipNonce");
        assertThatThrownBy(() -> new OwnedProcess(runId, "nonce", 0L, "4711@1", "2@2", TECHNIQUE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rootPid");
        assertThatThrownBy(() -> new OwnedProcess(runId, "nonce", 4711L, "4711@1", "2@2", "tech|nique", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain");
    }
}
