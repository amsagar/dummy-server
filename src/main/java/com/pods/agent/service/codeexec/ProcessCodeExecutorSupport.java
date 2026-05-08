package com.pods.agent.service.codeexec;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProcessCodeExecutorSupport {
    private static final String RESULT_MARKER = "__PODS_EXEC_RESULT__";
    private final ObjectMapper objectMapper = new ObjectMapper();

    CodeExecutionResult run(List<String> command, Map<String, Object> input) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();
            process.getOutputStream().write(objectMapper.writeValueAsBytes(input == null ? Map.of() : input));
            process.getOutputStream().close();
            byte[] stdoutBytes = process.getInputStream().readAllBytes();
            byte[] stderrBytes = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            String stdout = new String(stdoutBytes, StandardCharsets.UTF_8);
            String stderr = new String(stderrBytes, StandardCharsets.UTF_8);
            if (exit != 0) {
                return CodeExecutionResult.failure("Snippet process exited with status " + exit, stdout, stderr, false);
            }
            int markerIdx = stdout.lastIndexOf(RESULT_MARKER);
            if (markerIdx < 0) {
                return CodeExecutionResult.failure("Snippet did not emit execution result.", stdout, stderr, false);
            }
            String resultJson = stdout.substring(markerIdx + RESULT_MARKER.length()).trim();
            String userStdout = stdout.substring(0, markerIdx).trim();
            Object parsed = parseFlexibleBody(resultJson);
            return CodeExecutionResult.success(parsed, userStdout, stderr);
        } catch (Exception e) {
            return CodeExecutionResult.failure("Snippet execution failed: " + e.getMessage(), "", "", false);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    String resultMarker() {
        return RESULT_MARKER;
    }

    Object parseFlexibleBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception ignored) {
            return body;
        }
    }

    String jsonLiteral(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
    }

    Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }
}
