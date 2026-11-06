package io.aria.conductor.agent.service;

import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.common.model.SystemConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceTest {

    @Mock
    SystemConfigRepository repository;

    SystemConfigService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(repository);
    }

    private SystemConfig config(String key, String value) {
        return SystemConfig.builder()
                .configKey(key)
                .configValue(value)
                .description("test")
                .updatedAt(Instant.now())
                .build();
    }

    // --- get() ---

    @Test
    void get_returnsValue_whenKeyExists() {
        when(repository.findByConfigKey("my.key")).thenReturn(Optional.of(config("my.key", "hello")));

        assertThat(service.get("my.key", "default")).isEqualTo("hello");
    }

    @Test
    void get_returnsDefault_whenKeyMissing() {
        when(repository.findByConfigKey("missing.key")).thenReturn(Optional.empty());

        assertThat(service.get("missing.key", "fallback")).isEqualTo("fallback");
    }

    // --- getInt() ---

    @Test
    void getInt_returnsValue_whenValid() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "42")));

        assertThat(service.getInt("k", 10, 1, 100)).isEqualTo(42);
    }

    @Test
    void getInt_clampsToMin() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "-5")));

        assertThat(service.getInt("k", 10, 0, 100)).isEqualTo(0);
    }

    @Test
    void getInt_clampsToMax() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "999")));

        assertThat(service.getInt("k", 10, 0, 100)).isEqualTo(100);
    }

    @Test
    void getInt_returnsDefault_onInvalidNumber() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "abc")));

        assertThat(service.getInt("k", 10, 0, 100)).isEqualTo(10);
    }

    @Test
    void getInt_returnsDefault_whenKeyMissing() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.empty());

        assertThat(service.getInt("k", 25, 0, 100)).isEqualTo(25);
    }

    // --- getLong() ---

    @Test
    void getLong_returnsValue_whenValid() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "600000")));

        assertThat(service.getLong("k", 100L, 0L, 1_000_000L)).isEqualTo(600_000L);
    }

    @Test
    void getLong_clampsToRange() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "99999999")));

        assertThat(service.getLong("k", 100L, 0L, 1_000_000L)).isEqualTo(1_000_000L);
    }

    @Test
    void getLong_returnsDefault_onInvalidNumber() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "not-a-long")));

        assertThat(service.getLong("k", 500L, 0L, 1_000_000L)).isEqualTo(500L);
    }

    // --- getDouble() ---

    @Test
    void getDouble_returnsValue_whenValid() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "0.75")));

        assertThat(service.getDouble("k", 0.5, 0.0, 1.0)).isEqualTo(0.75);
    }

    @Test
    void getDouble_clampsToRange() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "2.5")));

        assertThat(service.getDouble("k", 0.5, 0.0, 1.0)).isEqualTo(1.0);
    }

    @Test
    void getDouble_returnsDefault_onInvalidNumber() {
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(config("k", "xyz")));

        assertThat(service.getDouble("k", 0.5, 0.0, 1.0)).isEqualTo(0.5);
    }

    // --- listAll() ---

    @Test
    void listAll_delegatesToRepository() {
        SystemConfig c1 = config("a", "1");
        SystemConfig c2 = config("b", "2");
        when(repository.findAll()).thenReturn(List.of(c1, c2));

        assertThat(service.listAll()).containsExactly(c1, c2);
    }

    // --- getAllAsMap() ---

    @Test
    void getAllAsMap_returnsKeyValueMap() {
        when(repository.findAll()).thenReturn(List.of(config("x", "10"), config("y", "20")));

        Map<String, Object> map = service.getAllAsMap();

        assertThat(map).containsEntry("x", "10").containsEntry("y", "20").hasSize(2);
    }

    // --- upsert() ---

    @Test
    void upsert_createsNew_whenKeyMissing() {
        when(repository.findByConfigKey("new.key")).thenReturn(Optional.empty());
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemConfig result = service.upsert("new.key", "val", "desc");

        assertThat(result.getConfigKey()).isEqualTo("new.key");
        assertThat(result.getConfigValue()).isEqualTo("val");
        assertThat(result.getDescription()).isEqualTo("desc");
        verify(repository).save(any(SystemConfig.class));
    }

    @Test
    void upsert_updatesExisting() {
        SystemConfig existing = config("exist.key", "old");
        when(repository.findByConfigKey("exist.key")).thenReturn(Optional.of(existing));
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemConfig result = service.upsert("exist.key", "new", null);

        assertThat(result.getConfigValue()).isEqualTo("new");
        assertThat(result.getDescription()).isEqualTo("test"); // unchanged
    }

    // --- updateValue() ---

    @Test
    void updateValue_updatesExistingKey() {
        SystemConfig existing = config("my.key", "old");
        when(repository.findByConfigKey("my.key")).thenReturn(Optional.of(existing));
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemConfig result = service.updateValue("my.key", "new");

        assertThat(result.getConfigValue()).isEqualTo("new");
    }

    @Test
    void updateValue_throws_whenKeyMissing() {
        when(repository.findByConfigKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateValue("missing", "val"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown config key");
    }

    // --- getByKey() ---

    @Test
    void getByKey_returnsConfig() {
        SystemConfig c = config("k", "v");
        when(repository.findByConfigKey("k")).thenReturn(Optional.of(c));

        assertThat(service.getByKey("k")).isSameAs(c);
    }

    @Test
    void getByKey_throws_whenMissing() {
        when(repository.findByConfigKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByKey("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
