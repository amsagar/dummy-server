package com.pods.agent.service.hooks;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class RuntimeHookAuditTrail {
    private final ConcurrentLinkedDeque<Map<String, Object>> entries = new ConcurrentLinkedDeque<>();

    public void add(String hookPoint, String hookName, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("hookPoint", hookPoint);
        row.put("hookName", hookName);
        row.put("timestamp", System.currentTimeMillis());
        row.put("payload", payload);
        entries.addFirst(row);
        while (entries.size() > 500) {
            entries.pollLast();
        }
    }
}

