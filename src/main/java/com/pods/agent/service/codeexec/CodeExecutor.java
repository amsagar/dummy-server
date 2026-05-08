package com.pods.agent.service.codeexec;

interface CodeExecutor {
    CodeExecutionResult execute(String code, java.util.Map<String, Object> input, int memoryLimitMb);
}
