package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component("fileWriteHandler")
public class FileWriteHandler implements ToolHandler {

    @Value("${tools.file.workspace-dir:data/workspace}")
    private String workspaceDir;

    @Override
    public String execute(Map<String, Object> arguments) {
        String path = Objects.toString(arguments.get("path"), "");
        String content = Objects.toString(arguments.get("content"), "");
        if (path.isEmpty()) return "Error: Missing required parameter: path";
        if (content.isEmpty()) return "Error: Missing required parameter: content";
        if (Path.of(path).isAbsolute()) {
            return "Error: Absolute paths are not allowed. Use a relative path within the workspace.";
        }
        try {
            String runWorkspace = Objects.toString(arguments.get("_workspaceDir"), null);
            Path baseDir = runWorkspace != null
                    ? Path.of(runWorkspace).toAbsolutePath().normalize()
                    : Path.of(workspaceDir).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            Path target = baseDir.resolve(path).normalize();
            if (!target.startsWith(baseDir)) {
                return "Error: Path traversal denied: " + path;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            log.info("Written {} ({} chars)", target, content.length());
            return "Written: " + path + " (" + content.length() + " chars)";
        } catch (Exception e) {
            log.error("Write file failed: {}", path, e);
            return "Error: " + e.getMessage();
        }
    }
}

