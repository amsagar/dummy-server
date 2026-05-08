package com.pods.agent.service.codeexec;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class JavaExecutor implements CodeExecutor {
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("\\bjava\\.io\\b"),
            Pattern.compile("\\bjava\\.net\\b"),
            Pattern.compile("\\bjava\\.nio\\.file\\b"),
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("\\bRuntime\\.getRuntime\\b"),
            Pattern.compile("\\bjava\\.lang\\.reflect\\b"),
            Pattern.compile("\\bClassLoader\\b"),
            Pattern.compile("\\bThread\\b")
    );

    @Override
    public CodeExecutionResult execute(String code, Map<String, Object> input, int memoryLimitMb) {
        String rawCode = code == null ? "" : code;
        String blocked = findBlockedReference(rawCode);
        if (blocked != null) {
            return CodeExecutionResult.failure("Blocked Java API usage detected: " + blocked, "", "", false);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return CodeExecutionResult.failure("Java compiler is not available in this runtime.", "", "", false);
        }
        String className = "Snippet";
        String source = """
                import java.util.Map;
                public class Snippet {
                    public static Object run(Map<String, Object> input) throws Exception {
                %s
                    }
                }
                """.formatted(indentBlock(rawCode, "        "));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        InMemoryFileManager fileManager = new InMemoryFileManager(standard);
        JavaFileObject sourceFile = new InMemorySourceFile(className, source);
        boolean ok = Boolean.TRUE.equals(compiler.getTask(
                null,
                fileManager,
                diagnostics,
                List.of("--release", "17"),
                null,
                List.of(sourceFile)
        ).call());
        if (!ok) {
            StringBuilder errors = new StringBuilder("Java compilation failed.");
            diagnostics.getDiagnostics().stream().limit(5).forEach(d ->
                    errors.append(" [line ").append(d.getLineNumber()).append("] ").append(d.getMessage(Locale.ROOT)));
            return CodeExecutionResult.failure(errors.toString(), "", "", false);
        }

        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        try {
            System.setOut(new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
            InMemoryClassLoader classLoader = new InMemoryClassLoader(fileManager.getClassBytes());
            Class<?> clazz = classLoader.loadClass(className);
            Object result = clazz.getMethod("run", Map.class).invoke(null, input == null ? Map.of() : input);
            return CodeExecutionResult.success(result,
                    stdoutBuffer.toString(StandardCharsets.UTF_8),
                    stderrBuffer.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return CodeExecutionResult.failure("Java snippet execution failed: " + e.getMessage(),
                    stdoutBuffer.toString(StandardCharsets.UTF_8),
                    stderrBuffer.toString(StandardCharsets.UTF_8),
                    false);
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    private String findBlockedReference(String code) {
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(code).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    private String indentBlock(String text, String indent) {
        if (text == null || text.isBlank()) return indent + "return null;\n";
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            out.append(indent).append(line).append('\n');
        }
        return out.toString();
    }

    private static final class InMemorySourceFile extends SimpleJavaFileObject {
        private final String code;

        private InMemorySourceFile(String className, String code) {
            super(URI.create("string:///" + className + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class InMemoryByteCode extends SimpleJavaFileObject {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private InMemoryByteCode(String className, JavaFileObject.Kind kind) {
            super(URI.create("bytes:///" + className + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return out;
        }

        public byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, InMemoryByteCode> compiled = new HashMap<>();

        private InMemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            InMemoryByteCode code = new InMemoryByteCode(className, kind);
            compiled.put(className, code);
            return code;
        }

        Map<String, byte[]> getClassBytes() {
            Map<String, byte[]> out = new HashMap<>();
            for (Map.Entry<String, InMemoryByteCode> entry : compiled.entrySet()) {
                out.put(entry.getKey(), entry.getValue().bytes());
            }
            return out;
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes;

        private InMemoryClassLoader(Map<String, byte[]> classBytes) {
            super(JavaExecutor.class.getClassLoader());
            this.classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) return super.findClass(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
