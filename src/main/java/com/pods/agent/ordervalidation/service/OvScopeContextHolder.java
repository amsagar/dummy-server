package com.pods.agent.ordervalidation.service;

/**
 * Thread-local scope marker. Set by {@code OvScopeInterceptor} at the
 * start of every OV-controlled request and cleared in a {@code finally}.
 * Downstream filter points (skill registry, rule-domain matcher,
 * decision-table service) consult this to decide whether to apply
 * allow-list restrictions.
 *
 * <p>{@link #current()} returns {@code null} when no OV scope is active
 * (i.e. the request came from the main admin UI). Filters should treat
 * a null scope as "no OV restriction" and behave unchanged.
 */
public final class OvScopeContextHolder {

    private static final ThreadLocal<OvScope> CURRENT = new ThreadLocal<>();

    private OvScopeContextHolder() {}

    public static void set(OvScope scope) {
        if (scope == null) clear();
        else CURRENT.set(scope);
    }

    public static OvScope current() {
        return CURRENT.get();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
