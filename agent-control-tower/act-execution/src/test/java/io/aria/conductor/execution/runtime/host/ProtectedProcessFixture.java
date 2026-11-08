package io.aria.conductor.execution.runtime.host;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test fixture: the creation identity of the protected System process (PID 4), read
 * through WMI instead of {@code OpenProcess}. PID 4 refuses
 * {@code PROCESS_QUERY_LIMITED_INFORMATION} with access denied (5), so it is the real
 * OS case that exercises the handle-free fallback added for F32: a stale {@code pid@ticks}
 * record whose pid is now held by a protected process must resolve to {@code mismatch}
 * rather than staying {@code unverifiable} forever.
 *
 * <p>The query returns the raw DMTF/ISO-8601 creation string and converts it here with
 * the offset it carries, so the fixture does not reuse the supervisor's own conversion
 * path. Millisecond precision is well inside the supervisor's 100 ms comparison
 * tolerance.
 */
final class ProtectedProcessFixture {

    private static final long FILETIME_TICKS_AT_EPOCH = 116_444_736_000_000_000L;
    private static final long TICKS_PER_MILLISECOND = 10_000L;

    private ProtectedProcessFixture() {
    }

    /** PID 4's creation time in FILETIME ticks (100 ns since 1601). */
    static long systemCreationTicks() throws Exception {
        Process probe = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_Process -Filter 'ProcessId=4').CreationDate.ToString('o')")
                .redirectErrorStream(true)
                .start();
        String output;
        try (var stream = probe.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertThat(probe.waitFor(60, TimeUnit.SECONDS))
                .as("the independent WMI fixture query for the protected process must finish")
                .isTrue();
        assertThat(probe.exitValue())
                .as("the independent WMI fixture query must succeed: " + output)
                .isZero();
        assertThat(output)
                .as("the protected process must expose a readable WMI creation time")
                .isNotBlank();
        return OffsetDateTime.parse(output).toInstant().toEpochMilli() * TICKS_PER_MILLISECOND
                + FILETIME_TICKS_AT_EPOCH;
    }
}
