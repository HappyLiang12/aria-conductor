package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@Component("fileListHandler")
public class FileListHandler implements ToolHandler {

    @Value("${tools.file.project-root:.}")
    private String projectRoot;

    @Override
    public String execute(Map<String, Object> arguments) {
        String path = Objects.toString(arguments.get("path"), ".");
        try {
            String runWorkspace = Objects.toString(arguments.get("_workspaceDir"), null);
            Path baseDir = runWorkspace != null
                    ? Path.of(runWorkspace).toAbsolutePath().normalize()
                    : Path.of(projectRoot).toAbsolutePath().normalize();
            Path dirPath = baseDir.resolve(path).normalize();
            if (!dirPath.startsWith(baseDir)) {
                return "Error: Path traversal denied: " + path;
            }
            if (!Files.exists(dirPath)) return "Error: Directory not found: " + path;
            if (!Files.isDirectory(dirPath)) return "Error: Not a directory: " + path;
            try (Stream<Path> stream = Files.list(dirPath)) {
                List<String> entries = stream
                        .map(p -> Files.isDirectory(p) ? p.getFileName().toString() + "/" : p.getFileName().toString())
                        .sorted()
                        .toList();
                if (entries.isEmpty()) {
                    return "Directory '" + path + "' is empty.";
                }
                return "Files in '" + path + "':\n  - " + String.join("\n  - ", entries);
            }
        } catch (Exception e) {
            log.error("Failed to list directory: {}", path, e);
            return "Error listing directory '" + path + "': " + e.getMessage();
        }
    }
}
