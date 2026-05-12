package com.pods.agent.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class SecurityContextService {
    public String currentUserIdOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }
        return auth.getName();
    }

    /**
     * Same as {@link #currentUserIdOrThrow()} but returns {@code defaultId}
     * instead of throwing when there is no authenticated user. Used by the
     * order-validation-ui surface where chat/sessions/models are exposed
     * permitAll and need a stable owner id for persistence — see
     * SecurityConfig for the permitAll routes and the rationale.
     */
    public String currentUserIdOrDefault(String defaultId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            return defaultId;
        }
        // Anonymous Spring authentication uses the principal "anonymousUser".
        if ("anonymousUser".equals(auth.getName())) return defaultId;
        return auth.getName();
    }
}
