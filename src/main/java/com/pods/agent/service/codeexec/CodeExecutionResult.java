package com.pods.agent.service.codeexec;

public record CodeExecutionResult(boolean success,
                                  Object output,
                                  String stdout,
                                  String stderr,
                                  String error,
                                  boolean timedOut) {
    public static CodeExecutionResult success(Object output, String stdout, String stderr) {
        return new CodeExecutionResult(true, output, sanitize(stdout), sanitize(stderr), null, false);
    }

    public static CodeExecutionResult failure(String error, String stdout, String stderr, boolean timedOut) {
        return new CodeExecutionResult(false, null, sanitize(stdout), sanitize(stderr), error, timedOut);
    }

    private static String sanitize(String text) {
        return text == null ? "" : text;
    }
}
