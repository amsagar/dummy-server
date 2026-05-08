package com.pods.agent.service.codeexec;

import com.pods.agent.config.RuntimeTuningProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeExecutionServiceTest {

    @Test
    void rejectsUnsupportedLanguage() {
        CodeExecutionService service = new CodeExecutionService(new RuntimeTuningProperties());
        CodeExecutionResult result = service.execute("ruby", "puts 'hi'", Map.of(), 1000L, 32);
        assertFalse(result.success());
        assertTrue(result.error().contains("Unsupported code language"));
    }

    @Test
    void javaExecutorBlocksSensitiveApis() {
        JavaExecutor executor = new JavaExecutor();
        CodeExecutionResult result = executor.execute(
                "java.io.File f = new java.io.File(\"/tmp\"); return f.getPath();",
                Map.of(),
                64
        );
        assertFalse(result.success());
        assertTrue(result.error().toLowerCase().contains("blocked"));
    }

    @Test
    void javascriptExecutorExposesNamedInputsAsVariables() {
        JavaScriptExecutor executor = new JavaScriptExecutor();
        CodeExecutionResult result = executor.execute(
                "return order.id;",
                Map.of("order", Map.of("id", "600030451")),
                64
        );
        assertTrue(result.success());
        assertEquals("600030451", String.valueOf(result.output()));
    }

    @Test
    void javascriptExecutorHandlesStatementBodiesWithConstDeclarations() {
        JavaScriptExecutor executor = new JavaScriptExecutor();
        CodeExecutionResult result = executor.execute(
                "const lines = order.lines || []; return lines.length;",
                Map.of("order", Map.of("lines", java.util.List.of(1, 2, 3))),
                64
        );
        assertTrue(result.success());
        assertEquals("3", String.valueOf(result.output()));
    }

    @Test
    void processSupportSurfacesChildStderrOnEarlyExit() {
        if (!nodeAvailable()) {
            return;
        }
        ProcessCodeExecutorSupport support = new ProcessCodeExecutorSupport();
        CodeExecutionResult result = support.run(
                List.of("node", "-e", "const __pods_code = const;"),
                Map.of("orderId", "600030451")
        );
        assertFalse(result.success());
        assertTrue(result.error().contains("status") || result.error().contains("stdin write failed"));
        String diagnostics = (result.stderr() + "\n" + result.stdout()).toLowerCase();
        assertTrue(diagnostics.contains("syntaxerror") || diagnostics.contains("unexpected token"));
    }

    private boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
