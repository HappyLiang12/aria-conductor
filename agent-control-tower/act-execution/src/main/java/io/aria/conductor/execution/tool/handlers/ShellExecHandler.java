package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component("shellExecHandler")
public class ShellExecHandler implements ToolHandler {

    @Value("${tools.shell.enabled:false}")
    private boolean shellEnabled;

    @Value("${tools.shell.whitelist:git,ls,cat,find,echo,mvn,npm,pnpm,curl}")
    private String whitelist;

    /**
     * Targets curl must not reach while shell is disabled (#66 review): curl is whitelisted so
     * agents can call external REST APIs, not so it can double as a local-file reader or an SSRF
     * probe into loopback/internal services (incl. the cloud metadata endpoint).
     */
    private static final java.util.regex.Pattern CURL_BLOCKED_TARGET = java.util.regex.Pattern.compile(
            "(?i)file://|localhost|127\\.0\\.0\\.1|\\[::1]|0\\.0\\.0\\.0|169\\.254\\.169\\.254");

    @Override
    public String execute(Map<String, Object> arguments) {
        String command = Objects.toString(arguments.get("command"), "");
        if (command.isEmpty()) return "Error: Missing required parameter: command";

        if (!shellEnabled) {
            // Security: reject ALL shell metacharacters that enable chaining/piping/redirection
            if (command.matches(".*[;|&`$<>\\\\\\n\\r].*")) {
                return "Error: Shell execution is disabled. Special characters are not allowed.";
            }
            // Check whitelist: allow specific commands even when globally disabled
            String cmd = command.trim().split("\\s+")[0].toLowerCase();
            Set<String> allowed = Arrays.stream(whitelist.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(String::toLowerCase).collect(Collectors.toSet());
            if (!allowed.contains(cmd)) {
                return "Error: Shell execution is disabled. Allowed commands: " + whitelist
                        + ". Set tools.shell.enabled=true for unrestricted access.";
            }
            if ("curl".equals(cmd) && CURL_BLOCKED_TARGET.matcher(command).find()) {
                return "Error: Shell execution is disabled. curl must not target local/internal"
                        + " addresses or file:// URLs.";
            }
        }
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.redirectErrorStream(true);
            // Set working directory to run workspace when available
            String workspaceDir = Objects.toString(arguments.get("_workspaceDir"), null);
            if (workspaceDir != null) {
                pb.directory(new java.io.File(workspaceDir));
            }
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                return "Exit code: " + exitCode + "\n" + output.trim();
            }
            return output.trim();
        } catch (Exception e) {
            log.error("Shell exec failed", e);
            return "Error: " + e.getMessage();
        }
    }
}

