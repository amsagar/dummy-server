package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.SkillRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Native tool callback that loads an enabled skill by name and returns a
 * {@code <skill_content ...>} block back to the model context.
 */
@Slf4j
public class SkillToolCallback implements ToolCallback {

    private static final String TOOL_NAME = "skill";
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"name":{"type":"string","description":"The exact skill name from available skills list"}},"required":["name"]}
            """;

    private final SkillRegistryService skillRegistryService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final SseEventSender sender;
    private final String sessionId;
    private final String turnId;
    private final ObjectMapper objectMapper;
    private final RuntimeEventRepository runtimeEventRepository;
    private final SkillExecutionGate skillExecutionGate;
    private final String toolIoLogMode;
    private final boolean productionEnvironment;

    public SkillToolCallback(SkillRegistryService skillRegistryService,
                             RuntimeTuningProperties runtimeTuningProperties,
                             SseEventSender sender,
                             String sessionId,
                             String turnId,
                             ObjectMapper objectMapper,
                             RuntimeEventRepository runtimeEventRepository,
                             SkillExecutionGate skillExecutionGate,
                             String toolIoLogMode,
                             boolean productionEnvironment) {
        this.skillRegistryService = skillRegistryService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.sender = sender;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.objectMapper = objectMapper;
        this.runtimeEventRepository = runtimeEventRepository;
        this.skillExecutionGate = skillExecutionGate;
        this.toolIoLogMode = toolIoLogMode == null ? "metadata" : toolIoLogMode.trim().toLowerCase(Locale.ROOT);
        this.productionEnvironment = productionEnvironment;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(buildDescription())
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String jsonInput) {
        return call(jsonInput, null);
    }

    @Override
    public String call(String jsonInput, ToolContext toolContext) {
        String payload = jsonInput == null ? "{}" : jsonInput;
        String callId = UUID.randomUUID().toString();
        logSkillMeta(callId, "call", payload, null, null);
        sender.sendToolCall(sessionId, callId, TOOL_NAME, payload);
        saveRuntimeEvent("tool.call", "{\"callId\":" + json(callId) + ",\"toolName\":\"" + TOOL_NAME + "\",\"input\":" + json(payload) + "}");

        try {
            String skillName = extractName(payload);
            if (skillName == null || skillName.isBlank()) {
                String msg = "Missing required input: name";
                sender.sendToolResult(sessionId, callId, TOOL_NAME, msg, "error");
                saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":\"" + TOOL_NAME + "\",\"status\":\"error\",\"output\":" + json(msg) + "}");
                logSkillMeta(callId, "done", payload, "error", msg);
                return msg;
            }
            SkillRegistryService.SkillSnapshot snapshot = skillRegistryService.getEnabledSkillByName(skillName);
            if (snapshot == null || snapshot.skill() == null) {
                String msg = "Skill \"" + skillName + "\" not found. Available skills: " + availableSkillNames();
                sender.sendToolResult(sessionId, callId, TOOL_NAME, msg, "error");
                saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":\"" + TOOL_NAME + "\",\"status\":\"error\",\"output\":" + json(msg) + "}");
                logSkillMeta(callId, "done", payload, "error", msg);
                return msg;
            }

            String output = buildSkillContent(snapshot);
            if (skillExecutionGate != null) {
                skillExecutionGate.markSkillLoaded();
            }
            sender.sendToolResult(sessionId, callId, TOOL_NAME, output, "success");
            saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":\"" + TOOL_NAME + "\",\"status\":\"success\",\"output\":" + json(truncate(output, 5000)) + "}");
            logSkillMeta(callId, "done", payload, "success", output);
            return output;
        } catch (Exception e) {
            String msg = "Skill load failed: " + e.getMessage();
            sender.sendToolResult(sessionId, callId, TOOL_NAME, msg, "error");
            saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":\"" + TOOL_NAME + "\",\"status\":\"error\",\"output\":" + json(msg) + "}");
            logSkillMeta(callId, "done", payload, "error", msg);
            return msg;
        }
    }

    private void logSkillMeta(String callId, String stage, String payload, String status, String output) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("callId", callId);
        meta.put("tool", TOOL_NAME);
        meta.put("stage", stage);
        meta.put("skillName", safeExtractName(payload));
        if (status != null) meta.put("status", status);
        if (output != null) meta.put("outputChars", output.length());
        log.info("[SkillToolCallback] tool_meta={}", toJsonQuiet(meta));
        if (shouldLogFullPayloads()) {
            log.debug("[SkillToolCallback] tool_payload callId={} stage={} input={} output={}",
                    callId, stage, payload, output);
        }
    }

    private String safeExtractName(String payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = objectMapper.readValue(payload == null ? "{}" : payload, Map.class);
            Object name = input.get("name");
            return name == null ? null : String.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldLogFullPayloads() {
        if ("full".equals(toolIoLogMode)) return true;
        if (!"full_nonprod_debug".equals(toolIoLogMode)) return false;
        return log.isDebugEnabled() && !productionEnvironment;
    }

    private String toJsonQuiet(Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return String.valueOf(meta);
        }
    }

    private String buildDescription() {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills == null || skills.isEmpty()) {
            return "Load a specialized skill. No skills are currently enabled.";
        }
        StringBuilder description = new StringBuilder();
        description.append("Load a specialized skill by name and inject full instructions as <skill_content>. ");
        description.append("Use this tool when the request matches one of the available skills.\n\n");
        description.append("<available_skills>\n");
        for (SkillRegistryService.SkillSnapshot snapshot : skills.stream()
                .sorted(Comparator.comparing(s -> safeName(s).toLowerCase(Locale.ROOT)))
                .toList()) {
            String name = safeName(snapshot);
            String skillDesc = snapshot.skill().getDescription() == null ? "" : snapshot.skill().getDescription().trim();
            description.append("- ").append(name);
            if (!skillDesc.isBlank()) {
                description.append(": ").append(skillDesc);
            }
            description.append("\n");
        }
        description.append("</available_skills>");
        return description.toString();
    }

    private String buildSkillContent(SkillRegistryService.SkillSnapshot snapshot) {
        String skillName = safeName(snapshot);
        int maxChars = Math.max(1000, runtimeTuningProperties.getMaxSkillContentChars());
        int maxFiles = Math.max(1, runtimeTuningProperties.getMaxSkillFilesPerSkill());
        String skillSlug = slug(skillName);
        String baseDir = "workspace://skills/" + skillSlug + "/";

        Map<String, String> files = snapshot.files() == null ? Map.of() : snapshot.files();
        String skillMd = files.entrySet().stream()
                .filter(e -> "SKILL.md".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");

        StringBuilder out = new StringBuilder();
        out.append("<skill_content name=\"").append(skillName).append("\">\n");
        out.append("# Skill: ").append(skillName).append("\n\n");
        if (!skillMd.isBlank()) {
            out.append(skillMd.trim()).append("\n");
        }
        out.append("\n");
        out.append("Base directory for this skill: ").append(baseDir).append("\n");
        out.append("Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\n");
        out.append("Note: file list is sampled.\n");

        List<Map.Entry<String, String>> otherFiles = new ArrayList<>(files.entrySet());
        otherFiles.removeIf(e -> "SKILL.md".equalsIgnoreCase(e.getKey()));
        otherFiles.sort(Map.Entry.comparingByKey());
        if (!otherFiles.isEmpty()) {
            out.append("\n<skill_files>\n");
            int count = 0;
            for (Map.Entry<String, String> entry : otherFiles) {
                if (count++ >= maxFiles) break;
                out.append("<file>").append(baseDir).append(entry.getKey()).append("</file>\n");
            }
            out.append("</skill_files>\n");
        }
        out.append("</skill_content>");

        String content = out.toString();
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n<!-- truncated -->";
    }

    private String extractName(String payload) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> input = objectMapper.readValue(payload, Map.class);
        Object value = input.get("name");
        return value == null ? null : String.valueOf(value).trim();
    }

    private String availableSkillNames() {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills == null || skills.isEmpty()) return "none";
        return skills.stream()
                .map(this::safeName)
                .filter(s -> !s.isBlank())
                .sorted(String::compareToIgnoreCase)
                .limit(30)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    private String slug(String value) {
        if (value == null || value.isBlank()) return "skill";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
    }

    private String safeName(SkillRegistryService.SkillSnapshot snapshot) {
        if (snapshot == null || snapshot.skill() == null || snapshot.skill().getName() == null) return "unknown-skill";
        return snapshot.skill().getName();
    }

    private void saveRuntimeEvent(String eventType, String payload) {
        if (runtimeEventRepository == null) return;
        try {
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .eventType(eventType)
                    .payload(payload)
                    .build());
        } catch (Exception e) {
            log.debug("[SkillToolCallback] runtime event save failed type={} error={}", eventType, e.getMessage());
        }
    }

    private String json(String value) {
        if (value == null) return "null";
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
