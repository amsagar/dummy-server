package com.pods.agent.workflow.plugin;

import com.pods.agent.service.codeexec.CodeExecutionRequest;
import com.pods.agent.service.codeexec.CodeExecutionResult;
import com.pods.agent.service.codeexec.CodeExecutionService;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Option;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Props;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Workflow plugin that runs a snippet of code via the project's existing
 * {@link CodeExecutionService} (Java / JavaScript / Python / TypeScript
 * executors are already wired there). We do not duplicate execution logic.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code language} (required) — e.g. {@code java}, {@code javascript},
 *       {@code python}, {@code typescript}.</li>
 *   <li>{@code code} (required) — source.</li>
 *   <li>{@code input} (optional) — Map of variables exposed to the script.</li>
 *   <li>{@code timeoutMs} (optional) — clamped by
 *       {@link CodeExecutionService#clampTimeout}.</li>
 *   <li>{@code memoryLimitMb} (optional) — clamped by
 *       {@link CodeExecutionService#clampMemory}.</li>
 * </ul>
 *
 * <p>Output: a Map with {@code success}, {@code output}, {@code stdout},
 * {@code stderr}, and on failure {@code error} / {@code timedOut}.
 */
@Component
@Slf4j
public class CodeExecPlugin implements ApplicationPlugin, DescribablePlugin {

    @Override
    public PluginDescriptor describe() {
        return PluginDescriptor.of(
                "CodeExecPlugin",
                "Run Code",
                "Executes a sandboxed code snippet and returns its output.",
                "code",
                "Code",
                List.of(
                        Props.options("language", "Language", true, "javascript", List.of(
                                Option.of("javascript", "JavaScript"),
                                Option.of("typescript", "TypeScript"),
                                Option.of("python", "Python"),
                                Option.of("java", "Java"))),
                        Props.code("code", "Code", true)
                                .withDescription("Source code. The runtime exposes 'input' as a binding."),
                        Props.json("input", "Input bindings", false)
                                .withDescription("Map of variables exposed to the sandbox.")
                                .withDefault("{}"),
                        Props.number("timeoutMs", "Timeout (ms)", false, 10000),
                        Props.number("memoryLimitMb", "Memory limit (MB)", false, 128)
                ));
    }


    private final CodeExecutionService codeExec;

    public CodeExecPlugin(CodeExecutionService codeExec) {
        this.codeExec = codeExec;
    }

    @Override
    public Object execute(Map<String, Object> props) {
        String language = require(props, "language");
        String code = require(props, "code");
        Map<String, Object> input = mapInput(props.get("input"));
        long timeoutMs = codeExec.clampTimeout(longOrNull(props.get("timeoutMs")));
        int memoryLimitMb = codeExec.clampMemory(intOrNull(props.get("memoryLimitMb")));

        if (!codeExec.isSupportedLanguage(language)) {
            throw new IllegalArgumentException("CodeExecPlugin: unsupported language: " + language);
        }

        CodeExecutionResult result = codeExec.execute(
                new CodeExecutionRequest(language, code, input, timeoutMs, memoryLimitMb));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.success());
        out.put("output", result.output());
        out.put("stdout", result.stdout());
        out.put("stderr", result.stderr());
        if (!result.success()) {
            out.put("error", result.error());
            out.put("timedOut", result.timedOut());
            // Surface as a tool failure so the workflow can route via error edges.
            // Include stdout/stderr tails so the engine's error message tells the
            // author *why* the snippet failed instead of just "did not emit result".
            throw new RuntimeException("code execution failed: " + result.error()
                    + (result.timedOut() ? " (timed out)" : "")
                    + formatStream("stderr", result.stderr())
                    + formatStream("stdout", result.stdout()));
        }
        return out;
    }

    private static String require(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) {
            throw new IllegalArgumentException("CodeExecPlugin requires '" + key + "' property");
        }
        return String.valueOf(v);
    }

    private static Long longOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static String formatStream(String label, String content) {
        if (content == null || content.isBlank()) return "";
        String trimmed = content.strip();
        if (trimmed.length() > 1500) {
            trimmed = "…" + trimmed.substring(trimmed.length() - 1500);
        }
        return " | " + label + ": " + trimmed;
    }

    private static Map<String, Object> mapInput(Object rawInput) {
        if (!(rawInput instanceof Map<?, ?> rawMap)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
