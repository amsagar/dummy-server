package com.pods.agent.service.codeexec;

import java.util.LinkedHashMap;
import java.util.Map;

public record CodeExecutionRequest(String language,
                                   String code,
                                   Map<String, Object> input,
                                   long timeoutMs,
                                   int memoryLimitMb) {
    public CodeExecutionRequest {
        input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    }
}
