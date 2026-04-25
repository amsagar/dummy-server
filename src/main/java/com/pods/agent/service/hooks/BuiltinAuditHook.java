package com.pods.agent.service.hooks;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BuiltinAuditHook implements RuntimeHook {
    private final RuntimeHookAuditTrail auditTrail;

    public BuiltinAuditHook(RuntimeHookAuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    public String name() {
        return "audit_log";
    }

    @Override
    public void execute(String hookPoint, Map<String, Object> payload, Map<String, Object> config) {
        auditTrail.add(hookPoint, name(), payload);
    }
}

