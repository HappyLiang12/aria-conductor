package io.aria.conductor.execution.adk;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "adk.runtime.langchain")
public class LangChainAdkProperties {

    private String host = "127.0.0.1";
    private int portRangeStart = 9300;
    private int portRangeEnd = 9400;
    private long shutdownTimeoutMs = 10000;
    private long maxRestartBackoffMs = 30000;
    private String pidDir = "./data/adk-pids";
    private String pythonPath = "python";
    private String serverScript = "../langchain-adk/src/server.py";
    private String mode = "subprocess"; // "subprocess" or "remote"
    private String llmBaseUrl = "https://api.deepseek.com/v1";
    private String llmDefaultModel = "deepseek-chat";
}
