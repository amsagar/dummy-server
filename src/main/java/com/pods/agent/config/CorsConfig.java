package com.pods.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsWebMvcConfigurer(CorsProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                var mapping = registry.addMapping(props.getPathPattern())
                        .allowedMethods(props.getAllowedMethods().toArray(String[]::new))
                        .allowedHeaders(props.getAllowedHeaders().toArray(String[]::new))
                        .allowCredentials(props.isAllowCredentials())
                        .maxAge(props.getMaxAge());

                if (!props.getExposedHeaders().isEmpty()) {
                    mapping.exposedHeaders(props.getExposedHeaders().toArray(String[]::new));
                }

                if (props.isAllowCredentials()) {
                    mapping.allowedOriginPatterns(props.getAllowedOrigins().toArray(String[]::new));
                } else {
                    mapping.allowedOrigins(props.getAllowedOrigins().toArray(String[]::new));
                }
            }
        };
    }
}
