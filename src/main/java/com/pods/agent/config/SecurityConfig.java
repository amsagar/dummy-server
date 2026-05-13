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
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // API-key filter runs first so an explicit X-API-Key header takes
        // precedence over any JWT that might also be present (e.g. when a
        // shell user copy-pastes both). JwtAuthFilter still handles the
        // common in-app case.
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Order-validation analytics: read-only, intentionally public
                        // so the standalone order-validation-ui can render without an
                        // auth flow. The endpoints expose only aggregates of completed
                        // workflow runs — see OrderValidationAnalyticsController.
                        .requestMatchers(HttpMethod.GET, "/api/v1/order-validation/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/order-validation/settings").permitAll()
                        // Order-validation UI also drives workflow runs (Submit order
                        // button) and decision-table CRUD (Settings → Decision Tables).
                        // Kept under permitAll to match the standalone-UI posture above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflow/runs").permitAll()
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
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
