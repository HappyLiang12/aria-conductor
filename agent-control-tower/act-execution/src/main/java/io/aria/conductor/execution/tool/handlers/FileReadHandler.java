package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component("fileReadHandler")
public class FileReadHandler implements ToolHandler {
    @Override
    public String execute(Map<String, Object> arguments) {
        String path = Objects.toString(arguments.get("path"), "");
        if (path.isEmpty()) return "Error: Missing required parameter: path";
        try {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) return "Error: File not found: " + path;
            if (!Files.isReadable(filePath)) return "Error: File not readable: " + path;
            return Files.readString(filePath);
        } catch (Exception e) {
            log.error("Failed to read file: {}", path, e);
            return "Error reading file '" + path + "': " + e.getMessage();
        }
    }
}
