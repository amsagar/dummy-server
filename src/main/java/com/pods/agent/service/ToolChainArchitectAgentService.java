package com.pods.agent.service;

import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolChainArchitectAgentService {
    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> LOCAL_TRACE_READ_TOOLS = java.util.Set.of("read", "grep");

    private final AgentRuntimeService agentRuntimeService;
    private final RuntimeEventRepository runtimeEventRepository;
    private final ObjectMapper objectMapper;

    public ToolChainArchitectAgentService(AgentRuntimeService agentRuntimeService,
                                          RuntimeEventRepository runtimeEventRepository,
                                          ObjectMapper objectMapper) {
        this.agentRuntimeService = agentRuntimeService;
        this.runtimeEventRepository = runtimeEventRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<SystemToolChainArtifact> generateFromTrace(Path workspace,
                                                               String traceRelativePath,
                                                               String sessionId,
                                                               String sourceTurnId,
                                                               String userId,
                                                               String userPrompt,
                                                               String assistantResponse,
                                                               com.pods.agent.domain.ModelRef modelRef,
                                                               String architectTurnId) {
        if (workspace == null || traceRelativePath == null || traceRelativePath.isBlank() || modelRef == null) {
            return Optional.empty();
        }

        String runtimeSessionId = (sessionId == null || sessionId.isBlank())
                ? "system-toolchain-architect-runtime"
                : sessionId;
        AgentSession runtimeSession = new AgentSession(runtimeSessionId);
        runtimeSession.setWorkspacePath(workspace);

        ChatState state = new ChatState();
        state.setRuntimeMode("toolchain_architect_runtime");
        state.setModel(modelRef);

        String prompt = buildArchitectPrompt(traceRelativePath);
        String raw = WorkspaceContextHolder.withWorkspace(workspace, () ->
                UserContextHolder.withUser(userId, () -> agentRuntimeService.runTurn(
                        runtimeSession,
                        prompt,
                        state,
                        new SseEventSender(null, objectMapper),
                        architectTurnId
                )));

        Optional<SystemToolChainArtifact> artifact = parseArtifact(raw);
        if (artifact.isEmpty()) return Optional.empty();
        List<RuntimeEvent> architectEvents = runtimeEventRepository.findByTurnId(architectTurnId);
        if (!hasSkillLoadEvidence(architectEvents, artifact.get().referencedSkills())
                || !hasTraceReadEvidence(architectEvents, traceRelativePath)) {
            return Optional.empty();
        }
        return artifact;
    }

    public Optional<SystemToolChainEligibility> evaluateEligibilityFromTrace(Path workspace,
                                                                              String traceRelativePath,
                                                                              String sessionId,
                                                                              String sourceTurnId,
                                                                              String userId,
                                                                              String userPrompt,
                                                                              String assistantResponse,
                                                                              com.pods.agent.domain.ModelRef modelRef,
                                                                              String eligibilityTurnId) {
        if (workspace == null || traceRelativePath == null || traceRelativePath.isBlank() || modelRef == null) {
            return Optional.empty();
        }

        String runtimeSessionId = (sessionId == null || sessionId.isBlank())
                ? "system-toolchain-eligibility-runtime"
                : sessionId;
        AgentSession runtimeSession = new AgentSession(runtimeSessionId);
        runtimeSession.setWorkspacePath(workspace);

        ChatState state = new ChatState();
        state.setRuntimeMode("toolchain_architect_runtime");
        state.setModel(modelRef);

        String prompt = buildEligibilityPrompt(traceRelativePath);
        String raw = WorkspaceContextHolder.withWorkspace(workspace, () ->
                UserContextHolder.withUser(userId, () -> agentRuntimeService.runTurn(
                        runtimeSession,
                        prompt,
                        state,
                        new SseEventSender(null, objectMapper),
                        eligibilityTurnId
                )));

        Optional<SystemToolChainEligibility> eligibility = parseEligibility(raw);
        if (eligibility.isEmpty()) return Optional.empty();
        List<RuntimeEvent> eligibilityEvents = runtimeEventRepository.findByTurnId(eligibilityTurnId);
        if (!hasSkillLoadEvidence(eligibilityEvents, eligibility.get().referencedSkills())
                || !hasTraceReadEvidence(eligibilityEvents, traceRelativePath)) {
            return Optional.empty();
        }
        return eligibility;
    }

    private String buildArchitectPrompt(String traceRelativePath) {
        return """
You are generating a reusable system toolchain from a completed chat turn trace.

HARD REQUIREMENTS:
1) First call the `skill` tool with `{ "name": "toolchain-architect" }`.
2) Read the transcript file at `%s` using LOCAL filesystem tools (`read` or `grep`).
3) Identify domain/reasoning skills relevant to this turn from trace evidence.
4) Call the `skill` tool for each relevant skill you identified (in addition to toolchain-architect).
5) NEVER use `webfetch` or URL tools for local files. Do not use `file://` URLs.
6) Analyze user request, reasoning, tool calls, and tool outputs in that file.
7) Return ONLY a JSON object (no prose, no markdown) with this schema:
{
  "name": "<toolchain name>",
  "description": "<what the chain does>",
  "intents": ["<intent1>", "<intent2>"],
  "referencedSkills": ["<skillName1>", "<skillName2>"],
  "graph": { "nodes": [], "edges": [] },
  "inputSchema": { "type": "object", "properties": {} },
  "outputSchema": { "type": "object", "properties": {} },
  "responseMode": "hybrid",
  "synthesisPrompt": "<final synthesis prompt>",
  "ragConfig": {}
}

Validation rules:
- graph.nodes and graph.edges must be present and non-empty.
- intents must contain at least one non-empty intent.
- graph must not contain any node with type "skill".
- referencedSkills must include only skills you actually loaded via skill tool.
- inputSchema and outputSchema must be JSON objects.
- Do not include commentary text around JSON.
""".formatted(traceRelativePath);
    }

    private String buildEligibilityPrompt(String traceRelativePath) {
        return """
You are deciding whether a reusable system toolchain should be created for a completed chat turn.

HARD REQUIREMENTS:
1) First call the `skill` tool with `{ "name": "toolchain-architect" }`.
2) Read the transcript file at `%s` using LOCAL filesystem tools (`read` or `grep`).
3) Identify domain/reasoning skills relevant to this turn from trace evidence.
4) Call the `skill` tool for each relevant skill you identified (in addition to toolchain-architect).
5) NEVER use `webfetch` or URL tools for local files. Do not use `file://` URLs.
6) Analyze complexity, number of substantive steps, tool usage depth, and reuse potential.
7) Return ONLY a JSON object (no prose, no markdown) with this schema:
{
  "isToolChainNeeded": true,
  "isSimpleTurn": false,
  "confidence": "high",
  "reason": "<short reason>",
  "referencedSkills": ["<skillName1>", "<skillName2>"]
}

Validation rules:
- confidence must be one of: high, medium, low.
- referencedSkills must include only skills you actually loaded via skill tool.
- Set isSimpleTurn=true for greetings/chitchat/trivial interactions.
- If isSimpleTurn=true then isToolChainNeeded must be false.
- Keep reason concise (<= 160 chars).
""".formatted(traceRelativePath);
    }

    private boolean hasSkillLoadEvidence(List<RuntimeEvent> events, List<String> referencedSkills) {
        Set<String> loaded = loadedSkillNames(events);
        if (!loaded.contains("toolchain-architect")) return false;
        if (referencedSkills == null || referencedSkills.isEmpty()) return true;
        for (String skill : referencedSkills) {
            String normalized = string(skill).toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) continue;
            if ("toolchain-architect".equals(normalized)) continue;
            if (!loaded.contains(normalized)) return false;
        }
        return true;
    }

    private boolean hasTraceReadEvidence(List<RuntimeEvent> events, String traceRelativePath) {
        if (traceRelativePath == null || traceRelativePath.isBlank()) return false;
        String normalizedTarget = traceRelativePath.replace("\\", "/").toLowerCase(Locale.ROOT);
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            if (!"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String toolName = string(payload.get("toolName"));
            if (!LOCAL_TRACE_READ_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) continue;
            Map<String, Object> input = toMap(parseFlexible(payload.get("input")));
            String path = string(input.get("path")).replace("\\", "/").toLowerCase(Locale.ROOT);
            if (path.endsWith(normalizedTarget) || path.contains(normalizedTarget)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Optional<SystemToolChainArtifact> parseArtifact(String raw) {
        String json = extractJsonObject(raw);
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String name = string(parsed.get("name"));
            String description = string(parsed.get("description"));
            List<String> intents = readStringList(parsed.get("intents"));
            String graphJson = toJson(parsed.get("graph"));
            String inputSchema = toJson(parsed.get("inputSchema"));
            String outputSchema = toJson(parsed.get("outputSchema"));
            String responseMode = string(parsed.get("responseMode"));
            String synthesisPrompt = string(parsed.get("synthesisPrompt"));
            Map<String, Object> ragConfig = toMap(parsed.get("ragConfig"));
            List<String> referencedSkills = normalizeReferencedSkills(parsed.get("referencedSkills"));

            if (name.isBlank() || description.isBlank() || intents.isEmpty()) return Optional.empty();
            if (!isObjectWithKeys(parsed.get("graph"), "nodes", "edges")) return Optional.empty();
            if (!isObject(parsed.get("inputSchema")) || !isObject(parsed.get("outputSchema"))) return Optional.empty();

            return Optional.of(SystemToolChainArtifact.builder()
                    .name(name)
                    .description(description)
                    .intents(intents)
                    .referencedSkills(referencedSkills)
                    .graphJson(graphJson)
                    .inputSchema(inputSchema)
                    .outputSchema(outputSchema)
                    .responseMode(responseMode.isBlank() ? "hybrid" : responseMode)
                    .synthesisPrompt(synthesisPrompt)
                    .ragConfig(ragConfig)
                    .build());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<SystemToolChainEligibility> parseEligibility(String raw) {
        String json = extractJsonObject(raw);
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            if (!(parsed.get("isToolChainNeeded") instanceof Boolean needed)) return Optional.empty();
            if (!(parsed.get("isSimpleTurn") instanceof Boolean simpleTurn)) return Optional.empty();
            String confidence = string(parsed.get("confidence")).toLowerCase(Locale.ROOT);
            if (!List.of("low", "medium", "high").contains(confidence)) return Optional.empty();
            String reason = string(parsed.get("reason"));
            if (reason.length() > 160) reason = reason.substring(0, 160);
            List<String> referencedSkills = normalizeReferencedSkills(parsed.get("referencedSkills"));
            if (simpleTurn) needed = false;
            return Optional.of(SystemToolChainEligibility.builder()
                    .toolChainNeeded(needed)
                    .simpleTurn(simpleTurn)
                    .confidence(confidence)
                    .reason(reason)
                    .referencedSkills(referencedSkills)
                    .build());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean isObject(Object value) {
        return value instanceof Map<?, ?>;
    }

    private boolean isObjectWithKeys(Object value, String... keys) {
        if (!(value instanceof Map<?, ?> map)) return false;
        for (String key : keys) {
            if (!map.containsKey(key)) return false;
        }
        return true;
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        Matcher fenced = FENCED_JSON.matcher(text);
        if (fenced.find()) text = fenced.group(1).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            return toMap(parsed);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Object parseFlexible(Object raw) {
        if (!(raw instanceof String s)) return raw;
        if (s.isBlank()) return "";
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception ignored) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object row : list) {
            if (row == null) continue;
            String text = String.valueOf(row).trim();
            if (!text.isBlank()) out.add(text);
        }
        return out;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String string(Object value) {
        if (value == null) return "";
        return String.valueOf(value).trim();
    }

    private List<String> normalizeReferencedSkills(Object raw) {
        List<String> skills = readStringList(raw);
        if (skills.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String row : skills) {
            String text = string(row);
            if (text.isBlank()) continue;
            if (out.stream().noneMatch(existing -> existing.equalsIgnoreCase(text))) {
                out.add(text);
            }
        }
        return out;
    }

    private Set<String> loadedSkillNames(List<RuntimeEvent> events) {
        Set<String> loaded = new LinkedHashSet<>();
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            if (!"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String toolName = string(payload.get("toolName"));
            if (!"skill".equalsIgnoreCase(toolName)) continue;
            Map<String, Object> input = toMap(parseFlexible(payload.get("input")));
            String skillName = string(input.get("name")).toLowerCase(Locale.ROOT);
            if (!skillName.isBlank()) loaded.add(skillName);
        }
        return loaded;
    }
}
