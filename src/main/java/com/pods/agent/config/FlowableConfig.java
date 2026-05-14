package com.pods.agent.config;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flowable BPMN process engine wiring. Reuses the application's primary
 * DataSource (auto-detected by the Spring Boot starter). Schema is
 * auto-created on first boot ({@code create-drop} would lose state across
 * restarts — we want {@code true} which is "create if missing").
 *
 * Compiled rule-domain BPMNs are deployed dynamically at compile time via
 * {@link org.flowable.engine.RepositoryService}; no static .bpmn20.xml files
 * are scanned from the classpath (we explicitly disable resource scanning to
 * avoid surprises).
 */
@Configuration
public class FlowableConfig {

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> processEngineConfigurer() {
        return cfg -> {
            cfg.setDatabaseSchemaUpdate("true");
            cfg.setAsyncExecutorActivate(true);
            // Don't auto-deploy any classpath BPMN — we deploy dynamically from DB.
            cfg.setDeploymentResources(new org.springframework.core.io.Resource[0]);
            // Keep full history (we surface it in the admin UI). Tune later if growth is an issue.
            cfg.setHistory("audit");
        };
    }
}
