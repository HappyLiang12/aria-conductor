package io.aria.conductor.agent.service;

import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.common.model.SystemConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SystemConfigService {

    private final SystemConfigRepository repository;

    public SystemConfigService(SystemConfigRepository repository) {
        this.repository = repository;
    }

    public String get(String key, String defaultValue) {
        return repository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue, int min, int max) {
        try {
            String raw = get(key, String.valueOf(defaultValue));
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            log.warn("Invalid int for config key '{}', using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue, long min, long max) {
        try {
            String raw = get(key, String.valueOf(defaultValue));
            long value = Long.parseLong(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            log.warn("Invalid long for config key '{}', using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue, double min, double max) {
        try {
            String raw = get(key, String.valueOf(defaultValue));
            double value = Double.parseDouble(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            log.warn("Invalid double for config key '{}', using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    public List<SystemConfig> listAll() {
        return repository.findAll();
    }

    public Map<String, Object> getAllAsMap() {
        Map<String, Object> map = new HashMap<>();
        for (SystemConfig config : repository.findAll()) {
            map.put(config.getConfigKey(), config.getConfigValue());
        }
        return map;
    }

    @Transactional
    public SystemConfig upsert(String key, String value, String description) {
        SystemConfig config = repository.findByConfigKey(key)
                .orElse(SystemConfig.builder()
                        .configKey(key)
                        .description(description)
                        .build());
        config.setConfigValue(value);
        if (description != null) {
            config.setDescription(description);
        }
        return repository.save(config);
    }

    @Transactional
    public SystemConfig updateValue(String key, String value) {
        SystemConfig config = repository.findByConfigKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown config key: " + key));
        config.setConfigValue(value);
        return repository.save(config);
    }

    public SystemConfig getByKey(String key) {
        return repository.findByConfigKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown config key: " + key));
    }
}
