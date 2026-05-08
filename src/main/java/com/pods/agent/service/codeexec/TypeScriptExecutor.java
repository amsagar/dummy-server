package com.pods.agent.service.codeexec;

import java.util.Map;
import java.util.regex.Pattern;

public class TypeScriptExecutor implements CodeExecutor {
    private static final Pattern TYPE_ANNOTATION = Pattern.compile("(?m)([\\w\\)\\]])\\s*:\\s*[A-Za-z_][A-Za-z0-9_<>,\\[\\]\\|\\s\\?]*");
    private static final Pattern INTERFACE_BLOCK = Pattern.compile("(?ms)^\\s*(interface|type)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=?.*?(\\n\\}|;)");
    private final JavaScriptExecutor javaScriptExecutor = new JavaScriptExecutor();

    @Override
    public CodeExecutionResult execute(String code, Map<String, Object> input, int memoryLimitMb) {
        String stripped = stripTypeScriptTypes(code == null ? "" : code);
        return javaScriptExecutor.execute(stripped, input, memoryLimitMb);
    }

    String stripTypeScriptTypes(String code) {
        String withoutDecls = INTERFACE_BLOCK.matcher(code).replaceAll("");
        return TYPE_ANNOTATION.matcher(withoutDecls).replaceAll("$1");
    }
}
