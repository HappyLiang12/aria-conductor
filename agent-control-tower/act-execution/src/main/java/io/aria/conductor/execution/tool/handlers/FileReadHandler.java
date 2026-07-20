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
@Component("fileReadHandler")
public class FileReadHandler implements ToolHandler {

    @Value("${tools.file.project-root:.}")
    private String projectRoot;

    @Override
    public String execute(Map<String, Object> arguments) {
        String path = Objects.toString(arguments.get("path"), "");
        if (path.isEmpty()) return "Error: Missing required parameter: path";
        try {
            Path baseDir = Path.of(projectRoot).toAbsolutePath().normalize();
            Path filePath = baseDir.resolve(path).normalize();
            if (!filePath.startsWith(baseDir)) {
                return "Error: Path traversal denied: " + path;
            }
            if (!Files.exists(filePath)) return "Error: File not found: " + path
                    + " (resolved against project root: " + baseDir + ")";
            if (!Files.isReadable(filePath)) return "Error: File not readable: " + path;
            String content = Files.readString(filePath);
            if (content.length() > 512_000) {
                return content.substring(0, 512_000) + "\n... [truncated at 512KB]";
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to read file: {}", path, e);
            return "Error reading file '" + path + "': " + e.getMessage();
        }
    }
}
