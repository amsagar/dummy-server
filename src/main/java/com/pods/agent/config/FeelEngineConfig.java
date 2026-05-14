package com.pods.agent.config;

import org.camunda.feel.FeelEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Camunda FEEL engine bean. Used inside BPMN JavaDelegates to evaluate
 * expressions over process variables (path nav, list filters, conditionals,
 * for-in comprehensions).
 *
 * Returned engine is thread-safe (engine state is immutable, evaluation
 * carries its own context map).
 */
@Configuration
public class FeelEngineConfig {

    @Bean
    public FeelEngine feelEngine() {
        return new FeelEngine.Builder().build();
    }
}
