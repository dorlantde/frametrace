package com.example.server.service;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
class ModelConfigurationTest {
    @Test void independentProvidersPreserveLegacyFallback() throws Exception {
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new PropertiesPropertySource("app", properties));
        env.getPropertySources().addFirst(new MapPropertySource("legacy", Map.of(
                "SILICONFLOW_API_KEY", "legacy", "SILICONFLOW_BASE_URL", "https://legacy/v1")));
        assertEquals("legacy", env.getProperty("ai.deepseek.api-key"));
        assertEquals("legacy", env.getProperty("ai.asr.api-key"));
        assertEquals("https://legacy/v1", env.getProperty("ai.embedding.base-url"));
        env.getPropertySources().addFirst(new MapPropertySource("overrides", Map.of(
                "LLM_API_KEY", "chat", "LLM_BASE_URL", "https://chat/v1",
                "ASR_API_KEY", "speech", "EMBEDDING_API_KEY", "vector",
                "EMBEDDING_BASE_URL", "https://vector/v1")));
        assertEquals("chat", env.getProperty("ai.deepseek.api-key"));
        assertEquals("speech", env.getProperty("ai.asr.api-key"));
        assertEquals("vector", env.getProperty("ai.embedding.api-key"));
        assertEquals("https://vector/v1", env.getProperty("ai.embedding.base-url"));
    }
}
