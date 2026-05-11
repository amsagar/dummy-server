package com.pods.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RuntimeTuningProperties.class, WorkflowProposalProperties.class})
public class RuntimeConfig {
}
