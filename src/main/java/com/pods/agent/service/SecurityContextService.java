package com.pods.agent.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class SecurityContextService {

    // Allow-list of identifiers the unauthenticated standalone UIs can pass
    // via the `X-OV-Client` header. Each UI sets its own value so that
    // chat sessions, messages, and HITL interactions land under that user
    // id and the sessions sidebar is naturally scoped per UI. Anything not
    // on this list is ignored to prevent random callers from impersonating
    // a different scope just by setting a header.
    private static final Set<String> ALLOWED_CLIENT_IDS = Set.of(
            "order-validation-ui",
            "vendor-rationalization-ui");
    private static final String CLIENT_HEADER = "X-OV-Client";

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
     * standalone UIs (order-validation-ui, vendor-rationalization-ui) where
     * chat/sessions/models are exposed permitAll and need a stable owner id
     * for persistence — see SecurityConfig for the permitAll routes.
     *
     * <p>If the request carries an {@code X-OV-Client} header naming one of
     * the allow-listed UI identifiers, that value wins over the supplied
     * {@code defaultId}. This is how sessions get partitioned per UI so the
     * vendor-rationalization sidebar doesn't surface order-validation chats
     * and vice versa.
     */
    public String currentUserIdOrDefault(String defaultId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean unauthenticated = auth == null
                || !auth.isAuthenticated()
                || auth.getName() == null
                || auth.getName().isBlank()
                || "anonymousUser".equals(auth.getName());
        if (!unauthenticated) return auth.getName();

        String clientId = readClientHeader();
        if (clientId != null && ALLOWED_CLIENT_IDS.contains(clientId)) {
            return clientId;
        }
        return defaultId;
    }

    private String readClientHeader() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sa) {
                HttpServletRequest req = sa.getRequest();
                String value = req.getHeader(CLIENT_HEADER);
                return value == null ? null : value.trim();
            }
        } catch (Exception ignored) {
            // Not in a request scope (background thread, scheduled task, etc.)
        }
        return null;
    }
}
