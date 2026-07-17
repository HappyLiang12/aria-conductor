package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component("webFetchHandler")
public class WebFetchHandler implements ToolHandler {
    @Override
    public String execute(Map<String, Object> arguments) {
        String url = Objects.toString(arguments.get("url"), "");
        if (url.isEmpty()) return "Error: Missing required parameter: url";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body.length() > 5000) {
                body = body.substring(0, 5000) + "\n... [truncated at 5000 characters]";
            }
            return "HTTP " + response.statusCode() + "\n\n" + body;
        } catch (Exception e) {
            log.error("Failed to fetch URL: {}", url, e);
            return "Error fetching URL '" + url + "': " + e.getMessage();
        }
    }
}
