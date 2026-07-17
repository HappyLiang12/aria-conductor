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
@Component("httpRequestHandler")
public class HttpRequestHandler implements ToolHandler {
    @Override
    public String execute(Map<String, Object> arguments) {
        String method = Objects.toString(arguments.get("method"), "GET").toUpperCase();
        String url = Objects.toString(arguments.get("url"), "");
        if (url.isEmpty()) return "Error: Missing required parameter: url";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));
            switch (method) {
                case "POST":
                    String body = Objects.toString(arguments.get("body"), "");
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                    break;
                case "PUT":
                    String putBody = Objects.toString(arguments.get("body"), "");
                    requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(putBody));
                    break;
                case "DELETE":
                    requestBuilder.DELETE();
                    break;
                default:
                    requestBuilder.GET();
                    break;
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            if (responseBody.length() > 5000) {
                responseBody = responseBody.substring(0, 5000) + "\n... [truncated at 5000 characters]";
            }
            return method + " " + url + " -> HTTP " + response.statusCode() + "\n\n" + responseBody;
        } catch (Exception e) {
            log.error("Failed to execute HTTP {} request to URL: {}", method, url, e);
            return "Error executing HTTP " + method + " request to '" + url + "': " + e.getMessage();
        }
    }
}
