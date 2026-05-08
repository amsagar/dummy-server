package com.pods.agent.service.codeexec;

import java.util.List;
import java.util.Map;

public class JavaScriptExecutor implements CodeExecutor {
    private final ProcessCodeExecutorSupport processSupport = new ProcessCodeExecutorSupport();

    @Override
    public CodeExecutionResult execute(String code, Map<String, Object> input, int memoryLimitMb) {
        String marker = processSupport.resultMarker();
        String wrapper = """
                const fs = require('fs');
                const raw = fs.readFileSync(0, 'utf8');
                const input = raw ? JSON.parse(raw) : {};
                function __pods_run(input) {
                %s
                }
                const __pods_result = __pods_run(input);
                process.stdout.write('\\n%s' + JSON.stringify(__pods_result));
                """.formatted(code == null ? "" : code, marker);
        return processSupport.run(List.of("node", "-e", wrapper), input);
    }
}
