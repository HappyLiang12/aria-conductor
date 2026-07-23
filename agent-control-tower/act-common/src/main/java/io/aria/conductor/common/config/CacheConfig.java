package io.aria.conductor.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                buildCache("agents", 100, 10),
                buildCache("sessions", 200, 5),
                buildCache("knowledge", 500, 15),
                buildCache("tools", 200, 1)
        ));
        return cacheManager;
    }

    private CaffeineCache buildCache(String name, int maxSize, int expireMinutes) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                        .recordStats()
                        .build());
    }
}
