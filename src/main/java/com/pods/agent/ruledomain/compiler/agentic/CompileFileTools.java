package com.pods.agent.ruledomain.compiler.agentic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Filesystem tools scoped to one compile workspace. The compile LLM uses
 * these to inspect the trace / skill / instructions written there by
 * {@link TraceCompileWorkspace}, and to submit its produced BPMN.
 *
 * <p>Each instance is per-compile (not a Spring singleton) — the
 * workspace path is the natural scope, and we don't want compiles
 * cross-talking.
 *
 * <p>Path safety: every path argument is resolved against the workspace
 * root and rejected if it escapes the root (no {@code ../}). Read sizes
 * are capped so a misbehaving LLM can't blow up the chat context.
 */
@Slf4j
public class CompileFileTools {

    private static final int MAX_READ_BYTES = 256 * 1024;     // 256 KB per file
    private static final int MAX_LIST_RESULTS = 200;
    private static final int MAX_GREP_RESULTS = 100;

    private final Path root;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> writtenBpmn = new AtomicReference<>();
    private final List<String> activityLog = new CopyOnWriteArrayList<>();

    public CompileFileTools(Path root, ObjectMapper objectMapper) {
        this.root = root.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    /** Bind all four tools as a Spring AI ToolCallback list. */
    public List<ToolCallback> callbacks() {
        return List.of(
                new ListFilesCallback(),
                new ReadFileCallback(),
                new GrepCallback(),
                new WriteBpmnCallback());
    }

    public String writtenBpmn() { return writtenBpmn.get(); }
    public void clearWrittenBpmn() { writtenBpmn.set(null); }
    public List<String> activityLog() { return Collections.unmodifiableList(activityLog); }

    // ── Tool: list_files ─────────────────────────────────────────────────

    private class ListFilesCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("compile_list_files")
                    .description("List files in the compile workspace. "
                            + "Accepts a glob pattern relative to the workspace root, "
                            + "e.g. \"trace/*.output.json\" or \"**/*.md\". "
                            + "Use \"**\" for all files.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"glob\":{\"type\":\"string\",\"description\":\"Glob pattern relative to workspace root\"}},\"required\":[\"glob\"]}")
                    .build();
        }

        @Override
        public String call(String jsonInput) {
            try {
                JsonNode in = objectMapper.readTree(jsonInput);
                String glob = in.path("glob").asString("**");
                List<String> matches = new ArrayList<>();
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .map(root::relativize)
                            .filter(matcher::matches)
                            .map(p -> p.toString().replace('\\', '/'))
                            .limit(MAX_LIST_RESULTS)
                            .forEach(matches::add);
                }
                activityLog.add("list_files(" + glob + ") → " + matches.size() + " matches");
                if (matches.isEmpty()) return "(no matches)";
                StringBuilder sb = new StringBuilder();
                for (String m : matches) sb.append(m).append("\n");
                return sb.toString();
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }
    }

    // ── Tool: read_file ──────────────────────────────────────────────────

    private class ReadFileCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("compile_read_file")
                    .description("Read the full content of a file in the compile workspace. "
                            + "Path is relative to workspace root. "
                            + "Up to " + MAX_READ_BYTES + " bytes; longer files are truncated with a marker.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path relative to workspace root\"}},\"required\":[\"path\"]}")
                    .build();
        }

        @Override
        public String call(String jsonInput) {
            try {
                JsonNode in = objectMapper.readTree(jsonInput);
                String path = in.path("path").asString("");
                Path resolved = resolveSafe(path);
                if (!Files.isRegularFile(resolved)) {
                    return "error: not a regular file: " + path;
                }
                byte[] bytes = Files.readAllBytes(resolved);
                activityLog.add("read_file(" + path + ") → " + bytes.length + " bytes");
                if (bytes.length <= MAX_READ_BYTES) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                return new String(bytes, 0, MAX_READ_BYTES, StandardCharsets.UTF_8)
                        + "\n\n... (file truncated at " + MAX_READ_BYTES + " bytes; " + bytes.length + " total)";
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }
    }

    // ── Tool: grep ───────────────────────────────────────────────────────

    private class GrepCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("compile_grep")
                    .description("Search file contents in the compile workspace for a regex pattern. "
                            + "Returns matching lines prefixed with `path:lineno:`. "
                            + "Use this to find e.g. a specific field name across the recorded trace responses.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"Regex pattern\"},\"glob\":{\"type\":\"string\",\"description\":\"Optional file glob (default: **)\"}},\"required\":[\"pattern\"]}")
                    .build();
        }

        @Override
        public String call(String jsonInput) {
            try {
                JsonNode in = objectMapper.readTree(jsonInput);
                String pattern = in.path("pattern").asString("");
                String glob = in.path("glob").asString("**");
                if (pattern.isBlank()) return "error: pattern is required";
                java.util.regex.Pattern re = java.util.regex.Pattern.compile(pattern);
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                StringBuilder out = new StringBuilder();
                int[] count = {0};
                try (var stream = Files.walk(root)) {
                    var iter = stream.filter(Files::isRegularFile)
                            .filter(p -> matcher.matches(root.relativize(p)))
                            .iterator();
                    while (iter.hasNext() && count[0] < MAX_GREP_RESULTS) {
                        Path p = iter.next();
                        List<String> lines;
                        try { lines = Files.readAllLines(p, StandardCharsets.UTF_8); }
                        catch (Exception ignored) { continue; }
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        for (int ln = 0; ln < lines.size() && count[0] < MAX_GREP_RESULTS; ln++) {
                            if (re.matcher(lines.get(ln)).find()) {
                                out.append(rel).append(':').append(ln + 1).append(": ").append(lines.get(ln)).append('\n');
                                count[0]++;
                            }
                        }
                    }
                }
                activityLog.add("grep(" + pattern + ", " + glob + ") → " + count[0] + " matches");
                if (count[0] == 0) return "(no matches)";
                if (count[0] >= MAX_GREP_RESULTS) out.append("... (truncated at ").append(MAX_GREP_RESULTS).append(" matches)\n");
                return out.toString();
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }
    }

    // ── Tool: write_bpmn ─────────────────────────────────────────────────

    private class WriteBpmnCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("compile_write_bpmn")
                    .description("Submit the produced BPMN XML for validation. "
                            + "Pass the COMPLETE BPMN as a single string in `xml`. "
                            + "The validator runs after this turn; if it fails you'll see "
                            + "the error on the next turn and can revise and call this again.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"xml\":{\"type\":\"string\",\"description\":\"Complete BPMN XML\"}},\"required\":[\"xml\"]}")
                    .build();
        }

        @Override
        public String call(String jsonInput) {
            try {
                JsonNode in = objectMapper.readTree(jsonInput);
                String xml = in.path("xml").asString("");
                if (xml == null || xml.isBlank()) {
                    return "error: `xml` is empty — pass the full BPMN as a single argument";
                }
                writtenBpmn.set(xml);
                activityLog.add("write_bpmn(" + xml.length() + " chars)");
                return "BPMN received (" + xml.length() + " chars). Validation runs at end of this attempt.";
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }
    }

    // ── Path safety ──────────────────────────────────────────────────────

    private Path resolveSafe(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes compile workspace: " + relative);
        }
        return resolved;
    }
}
