package com.pods.agent.ordervalidation.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Registers OV scope on every request whose path starts with
 * {@code /api/v1/order-validation}. Downstream filter points consult
 * {@link OvScopeContextHolder} to decide whether to apply allow-list
 * restrictions.
 *
 * <p>Cleared in {@code afterCompletion} so the scope never leaks into
 * background threads spawned later from a thread that handled an OV
 * request (the SSE chat streams its own work onto separate executors,
 * so they have to re-attach the scope explicitly — see
 * {@code OrderValidationChatController}).
 */
@Component
public class OvScopeInterceptor implements HandlerInterceptor {

    private final OvScopeService scopeService;

    public OvScopeInterceptor(OvScopeService scopeService) {
        this.scopeService = scopeService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        OvScopeContextHolder.set(scopeService.loadCurrent());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        OvScopeContextHolder.clear();
    }
}
