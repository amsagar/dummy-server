package com.pods.agent.service.codeexec;

import java.util.Map;

public class PythonExecutor implements CodeExecutor {
    private final ProcessCodeExecutorSupport processSupport = new ProcessCodeExecutorSupport();

    @Override
    public CodeExecutionResult execute(String code, Map<String, Object> input, int memoryLimitMb) {
        String marker = processSupport.resultMarker();
        String indentedCode = indentBlock(code == null ? "" : code, "    ");
        String wrapper = """
                import json
                import sys
                raw = sys.stdin.read()
                input = json.loads(raw) if raw else {}

                def __pods_run(input):
                %s

                __pods_result = __pods_run(input)
                sys.stdout.write("\\n%s" + json.dumps(__pods_result))
                """.formatted(indentedCode, marker);
        return processSupport.run(java.util.List.of("python3", "-c", wrapper), input);
    }

    private String indentBlock(String text, String indent) {
        if (text == null || text.isBlank()) return indent + "return None";
        String[] lines = text.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(indent).append(line).append('\n');
        }
        return out.toString();
    }
}
