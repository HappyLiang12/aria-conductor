package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component("shellExecHandler")
public class ShellExecHandler implements ToolHandler {

    @Value("${tools.shell.enabled:false}")
    private boolean shellEnabled;

    @Override
    public String execute(Map<String, Object> arguments) {
        if (!shellEnabled) {
            log.warn("shell_exec invoked but shell execution is disabled (set tools.shell.enabled=true to allow).");
            return "Error: Shell execution is disabled by default for security. Set tools.shell.enabled=true to enable.";
        }
        String command = Objects.toString(arguments.get("command"), "");
        if (command.isEmpty()) return "Error: Missing required parameter: command";
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.redirectErrorStream(true);
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

