package com.pods.agent.service.codeexec;

import java.util.List;
import java.util.Map;

public class JavaScriptExecutor implements CodeExecutor {
    private final ProcessCodeExecutorSupport processSupport = new ProcessCodeExecutorSupport();

    @Override
    public CodeExecutionResult execute(String code, Map<String, Object> input, int memoryLimitMb) {
        String marker = processSupport.resultMarker();
        String codeLiteral = processSupport.jsonLiteral(code == null ? "" : code);
        String wrapper = """
                const fs = require('fs');
                const raw = fs.readFileSync(0, 'utf8');
                const input = raw ? JSON.parse(raw) : {};
                const __pods_code = %s;
                const __pods_keys = Object.keys(input || {});
                const __pods_values = __pods_keys.map((k) => input[k]);
                const __pods_run = new Function('input', ...__pods_keys, __pods_code);
                const __pods_result = __pods_run(input, ...__pods_values);
                process.stdout.write('\\n%s' + JSON.stringify(__pods_result));
                """.formatted(codeLiteral, marker);
        return processSupport.run(List.of("node", "-e", wrapper), input);
    }
}
