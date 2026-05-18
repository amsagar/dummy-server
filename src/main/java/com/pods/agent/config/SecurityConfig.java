package com.pods.agent.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Order-validation surface: intentionally public so the
                        // standalone order-validation-ui can render without an auth
                        // flow. Analytics are read-only aggregates; the POSTs are
                        // chat (AI assistant) and workflow submission, both scoped
                        // to the configured workflow at the service layer — see
                        // OrderValidationAnalyticsController, OrderValidationChatController
                        // and OrderValidationWorkflowController.
                        .requestMatchers("/api/v1/order-validation/**").permitAll()
                        .requestMatchers("/api/v1/workflow/**").permitAll()
                        .requestMatchers("/api/v1/decision-tables/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/decision-tables").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/decision-tables").permitAll()
                        // AI Chat surface for the standalone order-validation-ui.
                        // Sessions/models endpoints expose data scoped to the
                        // default user the controller falls back to when no JWT
                        // is present (see SecurityContextService.currentUserIdOrDefault).
                        // Vendor rationalization portal: read-only analytics + reload + tunable config editor
                        .requestMatchers(HttpMethod.GET, "/api/v1/vendor-rationalization/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/vendor-rationalization/reload").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/vendor-rationalization/config").permitAll()
                        .requestMatchers("/api/v1/chat/**").permitAll()
                        .requestMatchers("/api/v1/sessions/**").permitAll()
                        .requestMatchers("/api/v1/models/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
