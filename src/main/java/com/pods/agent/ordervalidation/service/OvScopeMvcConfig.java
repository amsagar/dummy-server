package com.pods.agent.ordervalidation.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link OvScopeInterceptor} into the MVC pipeline. Scoped to
 * {@code /api/v1/order-validation/**} so only the OV-UI routes pay the
 * scope-load cost.
 */
@Configuration
public class OvScopeMvcConfig implements WebMvcConfigurer {

    private final OvScopeInterceptor interceptor;

    public OvScopeMvcConfig(OvScopeInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v1/order-validation/**");
    }
}
