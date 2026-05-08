package com.pods.agent.service.codeexec;

import com.pods.agent.config.RuntimeTuningProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class CodeExecutionService {
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final Map<String, CodeExecutor> executors;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public CodeExecutionService(RuntimeTuningProperties runtimeTuningProperties) {
        this.runtimeTuningProperties = runtimeTuningProperties;
        Map<String, CodeExecutor> impl = new LinkedHashMap<>();
        impl.put("javascript", new JavaScriptExecutor());
        impl.put("typescript", new TypeScriptExecutor());
        impl.put("python", new PythonExecutor());
        impl.put("java", new JavaExecutor());
        this.executors = Map.copyOf(impl);
    }

    public CodeExecutionResult execute(CodeExecutionRequest request) {
        String language = normalizeLanguage(request == null ? null : request.language());
        CodeExecutor executor = executors.get(language);
        if (executor == null) {
            return CodeExecutionResult.failure("Unsupported code language: " + language, "", "", false);
        }
        String code = request == null ? "" : String.valueOf(request.code() == null ? "" : request.code());
        if (code.isBlank()) {
            return CodeExecutionResult.failure("Code snippet cannot be empty.", "", "", false);
        }
        long timeoutMs = clampTimeout(request == null ? null : request.timeoutMs());
        int memoryMb = clampMemory(request == null ? null : request.memoryLimitMb());
        Map<String, Object> input = request == null || request.input() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.input());
        Future<CodeExecutionResult> future = executorService.submit(() -> executor.execute(code, input, memoryMb));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            return CodeExecutionResult.failure("Code execution timed out after " + timeoutMs + "ms.", "", "", true);
        } catch (Exception e) {
            return CodeExecutionResult.failure("Code execution failed: " + e.getMessage(), "", "", false);
        }
    }

    public CodeExecutionResult execute(String language,
                                       String code,
                                       Map<String, Object> input,
                                       Long timeoutMs,
                                       Integer memoryLimitMb) {
        return execute(new CodeExecutionRequest(
                language,
                code,
                input,
                timeoutMs == null ? 0L : timeoutMs,
                memoryLimitMb == null ? 0 : memoryLimitMb
        ));
    }

    public boolean isSupportedLanguage(String language) {
        return executors.containsKey(normalizeLanguage(language));
    }

    public long clampTimeout(Long timeoutMs) {
        long configuredDefault = Math.max(250L, runtimeTuningProperties.getCodeExecutionDefaultTimeoutMs());
        long configuredMax = Math.max(configuredDefault, runtimeTuningProperties.getCodeExecutionMaxTimeoutMs());
        if (timeoutMs == null || timeoutMs <= 0L) return configuredDefault;
        return Math.max(250L, Math.min(timeoutMs, configuredMax));
    }

    public int clampMemory(Integer memoryLimitMb) {
        int configuredDefault = Math.max(16, runtimeTuningProperties.getCodeExecutionDefaultMemoryLimitMb());
        int configuredMax = Math.max(configuredDefault, runtimeTuningProperties.getCodeExecutionMaxMemoryLimitMb());
        if (memoryLimitMb == null || memoryLimitMb <= 0) return configuredDefault;
        return Math.max(16, Math.min(memoryLimitMb, configuredMax));
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return "javascript";
        return language.trim().toLowerCase(Locale.ROOT);
    }
}
