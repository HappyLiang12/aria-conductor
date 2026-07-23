package io.aria.conductor.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.common.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@link HarnessProfile} for an agent. Profiles are stored as JSON in the
 * {@code system_config} table under {@code harness.profile.<name>} (runtime-configurable, same
 * pattern as {@code circuit.breaker.*}), with the global fallback name in
 * {@code harness.default.profile}.
 *
 * <p>Resolution precedence for the profile: {@code agent.config.harnessProfile} →
 * {@code harness.default.profile} → the built-in {@link HarnessProfile#defaults()}. Parsing is
 * defensive: any malformed or missing profile falls back to {@code defaults()} so a bad config
 * can never break a run. Each field is merged over {@code defaults()} so partial profiles are
 * safe.
 */
@Slf4j
@Service
public class HarnessProfileService {

    static final String KEY_PREFIX = "harness.profile.";
    static final String DEFAULT_PROFILE_KEY = "harness.default.profile";

    private final SystemConfigService systemConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache parsed profiles by name; short TTL so admin edits propagate within a minute
    // (mirrors ToolRiskResolver).
    private final Cache<String, HarnessProfile> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public HarnessProfileService(SystemConfigService systemConfig) {
        this.systemConfig = systemConfig;
    }

    /** Resolve the effective profile for an agent (reads {@code agent.config.harnessProfile}). */
    public HarnessProfile resolve(Agent agent) {
        String name = profileNameFromAgentConfig(agent);
        if (name == null || name.isBlank()) {
            name = systemConfig.get(DEFAULT_PROFILE_KEY, "default");
        }
        return resolveByName(name);
    }

    /** Resolve a profile by name (cached). Unknown names fall back to {@code defaults()}. */
    public HarnessProfile resolveByName(String name) {
        if (name == null || name.isBlank()) return HarnessProfile.defaults();
        return cache.get(name, this::loadAndParse);
    }

    /** All configured profiles (for the read API). Always includes a synthesized default. */
    public List<HarnessProfile> listProfiles() {
        List<HarnessProfile> profiles = new ArrayList<>();
        boolean sawDefault = false;
        for (var cfg : systemConfig.listAll()) {
            String key = cfg.getConfigKey();
            if (key != null && key.startsWith(KEY_PREFIX)) {
                String name = key.substring(KEY_PREFIX.length());
                profiles.add(resolveByName(name));
                if ("default".equals(name)) sawDefault = true;
            }
        }
        if (!sawDefault) profiles.add(HarnessProfile.defaults());
        return profiles;
    }

    /** Remove denylisted tools from a resolved tool set (used by the loop and getAgentTools). */
    public List<ToolDefinition> applyDenylist(List<ToolDefinition> tools, HarnessProfile profile) {
        if (tools == null || tools.isEmpty() || profile == null
                || profile.toolDenylist() == null || profile.toolDenylist().isEmpty()) {
            return tools;
        }
        return tools.stream().filter(t -> !profile.denies(t.getName())).toList();
    }

    private String profileNameFromAgentConfig(Agent agent) {
        if (agent == null || agent.getConfig() == null || agent.getConfig().isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(agent.getConfig());
            JsonNode hp = node.get("harnessProfile");
            return hp != null && hp.isTextual() ? hp.asText() : null;
        } catch (Exception e) {
            log.debug("Could not read harnessProfile from agent config: {}", e.getMessage());
            return null;
        }
    }

    private HarnessProfile loadAndParse(String name) {
        String json = systemConfig.get(KEY_PREFIX + name, null);
        if (json == null || json.isBlank()) {
            if (!"default".equals(name)) {
                log.warn("Harness profile '{}' not found; using defaults()", name);
            }
            return HarnessProfile.defaults();
        }
        try {
            return parse(name, json);
        } catch (Exception e) {
            log.warn("Malformed harness profile '{}' ({}); using defaults()", name, e.getMessage());
            return HarnessProfile.defaults();
        }
    }

    /** Parse a profile JSON, merging each field over {@link HarnessProfile#defaults()}. */
    HarnessProfile parse(String name, String json) throws Exception {
        HarnessProfile d = HarnessProfile.defaults();
        JsonNode n = objectMapper.readTree(json);

        List<String> denylist = readStringList(n.get("toolDenylist"), d.toolDenylist());

        JsonNode steerNode = n.get("steering");
        boolean shellToGit = steerNode != null && steerNode.hasNonNull("shellExecToGitPack")
                ? steerNode.get("shellExecToGitPack").asBoolean(d.steering().shellExecToGitPack())
                : d.steering().shellExecToGitPack();

        JsonNode svNode = n.get("selfVerify");
        boolean svEnabled = d.selfVerify().enabled();
        List<String> escalateTiers = d.selfVerify().escalateTiers();
        int maxTokens = d.selfVerify().maxResponseTokens();
        String promptOverride = d.selfVerify().promptOverride();
        if (svNode != null) {
            if (svNode.hasNonNull("enabled")) svEnabled = svNode.get("enabled").asBoolean(svEnabled);
            escalateTiers = readStringList(svNode.get("escalateTiers"), escalateTiers);
            if (svNode.hasNonNull("maxResponseTokens")) maxTokens = svNode.get("maxResponseTokens").asInt(maxTokens);
            if (svNode.hasNonNull("promptOverride")) promptOverride = svNode.get("promptOverride").asText(null);
        }

        int rounds = n.hasNonNull("maxToolCallRounds")
                ? n.get("maxToolCallRounds").asInt(d.maxToolCallRounds()) : d.maxToolCallRounds();
        int outCap = n.hasNonNull("maxToolOutputChars")
                ? n.get("maxToolOutputChars").asInt(d.maxToolOutputChars()) : d.maxToolOutputChars();
        String resolvedName = n.hasNonNull("name") ? n.get("name").asText(name) : name;

        return new HarnessProfile(
                resolvedName,
                denylist,
                new HarnessProfile.Steering(shellToGit),
                new HarnessProfile.SelfVerify(svEnabled, escalateTiers, maxTokens, promptOverride),
                rounds,
                outCap);
    }

    private List<String> readStringList(JsonNode arr, List<String> fallback) {
        if (arr == null || !arr.isArray()) return fallback;
        List<String> out = new ArrayList<>();
        arr.forEach(e -> { if (e != null && e.isTextual()) out.add(e.asText()); });
        return out;
    }

    /** Visible for tests: clear the profile cache. */
    public void clearCache() {
        cache.invalidateAll();
    }
}
