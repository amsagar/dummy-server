package com.pods.agent.config;

import com.pods.agent.service.AzureBlobSkillFileStorageService;
import com.pods.agent.service.InMemorySkillFileStorageService;
import com.pods.agent.service.SkillFileStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SkillBlobProperties.class)
public class SkillStorageConfig {
    @Bean
    public SkillFileStorageService skillFileStorageService(SkillBlobProperties props) {
        if (props.getConnectionString() == null || props.getConnectionString().isBlank()) {
            if (!props.isAllowInMemoryFallback()) {
                throw new IllegalStateException("Azure Blob connection string is required when in-memory fallback is disabled");
            }
            return new InMemorySkillFileStorageService();
        }
        validateConnectionString(props.getConnectionString(), props.isAllowInMemoryFallback());
        return new AzureBlobSkillFileStorageService(props);
    }

    private void validateConnectionString(String connectionString, boolean allowFallback) {
        String normalized = connectionString.trim();
        boolean valid =
                normalized.contains("UseDevelopmentStorage=true") ||
                (normalized.contains("AccountName=") && normalized.contains("AccountKey="));
        if (!valid) {
            if (allowFallback) {
                throw new IllegalStateException("Invalid Azure Blob connection string format");
            }
            throw new IllegalStateException("Invalid Azure Blob connection string; startup blocked by strict mode");
        }
    }
}
