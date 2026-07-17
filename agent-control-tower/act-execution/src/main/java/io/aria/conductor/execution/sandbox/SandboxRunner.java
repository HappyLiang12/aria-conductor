package io.aria.conductor.execution.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SandboxRunner {

    private static final String DEFAULT_IMAGE = "alpine:latest";
    private static final Pattern SAFE_SCRIPT_PATTERN = Pattern.compile("^[\\x20-\\x7E\\n\\r\\t]*$");
    private String containerRuntime;

    public SandboxRunner() {
        detectRuntime();
    }

    private void detectRuntime() {
        if (commandExists("docker")) containerRuntime = "docker";
        else if (commandExists("podman")) containerRuntime = "podman";
        else { containerRuntime = null; log.warn("No container runtime detected. Sandbox tools disabled."); }
    }

    public boolean isSandboxAvailable() { return containerRuntime != null; }
    public String getRuntime() { return containerRuntime; }

    static void validateScript(String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("Script must not be blank");
        }
        if (!SAFE_SCRIPT_PATTERN.matcher(script).matches()) {
            throw new IllegalArgumentException("Script contains disallowed characters");
        }
    }

    public SandboxResult execute(String script, String scriptType, int memoryMb, String cpuLimit, int timeoutMs) {
        if (!isSandboxAvailable()) return SandboxResult.failed("No container runtime available");

        validateScript(script);
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile("act-sandbox-", ".sh");
            Files.writeString(tempScript, script);
            tempScript.toFile().setExecutable(true);

            String entrypoint = "python".equalsIgnoreCase(scriptType) ? "python3" : "/bin/sh";
            String command = buildRunCommand(containerRuntime, tempScript.toString(), memoryMb, cpuLimit, timeoutMs, entrypoint);

            ProcessBuilder pb = new ProcessBuilder(command.split(" "));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return SandboxResult.failed("Timeout after " + timeoutMs + "ms");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) output.append(line).append("\n");
            }
            int code = process.exitValue();
            return code == 0
                ? SandboxResult.success(output.toString().trim())
                : SandboxResult.failed("Exit " + code + ": " + output.toString().trim());
        } catch (Exception e) {
            log.error("Sandbox failed", e);
            return SandboxResult.failed(e.getMessage());
        } finally {
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); }
                catch (IOException ignored) { }
            }
        }
    }

    static String buildRunCommand(String runtime, String hostScriptPath, int memoryMb, String cpuLimit, int timeoutMs, String entrypoint) {
        return String.format(
            "%s run --rm --memory=%dm --cpus=%s --network=none --read-only " +
            "--tmpfs /tmp:rw,noexec,nosuid,size=64m " +
            "-v %s:/script.sh:ro --entrypoint %s %s /script.sh",
            runtime, memoryMb, cpuLimit, hostScriptPath.replace("\\", "/"),
            entrypoint, DEFAULT_IMAGE);
    }

    private static boolean commandExists(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(System.getProperty("os.name").toLowerCase().contains("win") ? new String[]{"where", cmd} : new String[]{"which", cmd});
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}