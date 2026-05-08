package com.pods.agent.service.codeexec;

import com.pods.agent.config.RuntimeTuningProperties;
import org.junit.jupiter.api.Test;

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
}
