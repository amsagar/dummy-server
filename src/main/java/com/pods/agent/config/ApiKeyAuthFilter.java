package com.pods.agent.config;

import com.pods.agent.workflow.persistence.WorkflowApiKeyRow;
import com.pods.agent.workflow.service.WorkflowApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying an {@code X-API-Key} header against the
 * {@code workflow_api_key} table. Runs before {@link JwtAuthFilter} in the
 * security chain and only acts when the header is present — JWT auth still
 * handles everything else.
 *
 * <p>On a hit:
 * <ul>
 *   <li>SecurityContext is populated with the key's owner as the principal,
 *       same shape as JWT auth, so existing controllers see a familiar
 *       {@code Authentication}.</li>
 *   <li>The matching {@link WorkflowApiKeyRow} is stashed as the
 *       {@link #API_KEY_ROW_ATTR} request attribute so downstream code (run
 *       start) can enforce the key's process-def scope.</li>
 * </ul>
 *
 * <p>On an explicit miss (header present but invalid/revoked), we reject
 * with 401 — the caller clearly intended API-key auth and we shouldn't
 * silently fall through to "no credentials".
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String API_KEY_ROW_ATTR = "pods.workflowApiKey";

    private final WorkflowApiKeyService apiKeyService;

    public ApiKeyAuthFilter(WorkflowApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(API_KEY_HEADER);
        if (header == null || header.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            // Already authenticated (shouldn't happen — we run first — but be defensive).
            filterChain.doFilter(request, response);
            return;
        }
        Optional<WorkflowApiKeyRow> match = apiKeyService.verify(header.trim());
        if (match.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid or revoked API key\"}");
            return;
        }
        WorkflowApiKeyRow row = match.get();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                row.ownerId(), null, Collections.emptyList());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.setAttribute(API_KEY_ROW_ATTR, row);
        filterChain.doFilter(request, response);
    }
}
