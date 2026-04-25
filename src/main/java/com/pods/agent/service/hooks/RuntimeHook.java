package com.pods.agent.service.hooks;

import java.util.Map;

public interface RuntimeHook {
    String name();

    void execute(String hookPoint, Map<String, Object> payload, Map<String, Object> config);
}

