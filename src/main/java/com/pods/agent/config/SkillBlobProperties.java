package com.pods.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pods.skills.blob")
public class SkillBlobProperties {
    private String connectionString = "";
    private String container = "skills";
    private boolean allowInMemoryFallback = true;

    public String getConnectionString() {
        return connectionString;
    }

    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }

    public String getContainer() {
        return container;
    }

    public void setContainer(String container) {
        this.container = container;
    }

    public boolean isAllowInMemoryFallback() {
        return allowInMemoryFallback;
    }

    public void setAllowInMemoryFallback(boolean allowInMemoryFallback) {
        this.allowInMemoryFallback = allowInMemoryFallback;
    }
}
