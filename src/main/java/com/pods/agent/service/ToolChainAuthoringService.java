package com.pods.agent.service;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls an LLM to author the human-facing pieces of a system-suggested toolchain
 * from a recorded turn (tool inputs + outputs + the user prompt).
 *
 * Output: name, description, example intents, and a per-node argMappings block
 * expressed as JSONata against the runtime context shape (chainInput,
 * tool_N.input, tool_N.output).
 *
 * Always best-effort. Any failure (LLM down, schema validation fails, parse fails)
 * returns Optional.empty() so the caller can fall back to deterministic suggestion.
 */
@Service
@Slf4j
public class ToolChainAuthoringService {

    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TOOL_OUTPUT_CHARS = 4000;
    /** Mirror of ToolChainSuggestionService.AGENT_LOOP_INFRASTRUCTURE_TOOLS — keep in sync. */
    private static final java.util.Set<String> AGENT_LOOP_INFRASTRUCTURE_TOOLS = java.util.Set.of("skill");

    private final ModelProviderRouter modelProviderRouter;
    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public ToolChainAuthoringService(ModelProviderRouter modelProviderRouter,
                                     ToolRegistryService toolRegistryService,
                                     ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    public Optional<AuthoringResult> author(String userPrompt,
                                            List<RuntimeEvent> turnEvents,
                                            ModelRef modelRef) {
        return author(userPrompt, turnEvents, modelRef, null);
    }

    /**
     * Re-author the chain with corrective feedback from {@link MappingValidator}. The feedback
     * string lists each failed mapping (expected vs. resolved) and tells the LLM to fix them.
     * Returns Optional.empty() on any failure — the caller should keep the previous attempt.
     */
    public Optional<AuthoringResult> author(String userPrompt,
                                            List<RuntimeEvent> turnEvents,
                                            ModelRef modelRef,
                                            String corrective) {
        if (modelRef == null) return Optional.empty();
        List<RecordedToolCall> calls = pairCallsAndResults(turnEvents);
        if (calls.size() < 2) return Optional.empty();

        // The skill tool is filtered out of the chain's tool calls (it's agent-loop
        // infrastructure), but its OUTPUT — the loaded skill markdown — is the authoritative
        // rules sheet for transformations the chain needs to do downstream. Capture it
        // separately and feed to the LLM as context so it doesn't have to re-derive things
        // like "IDEL → ID for serviceType" from JSON Schema alone.
        List<Map<String, Object>> skillContexts = extractSkillContexts(turnEvents);

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("userPrompt", userPrompt == null ? "" : userPrompt);
        userPayload.put("toolCalls", calls.stream().map(this::renderCall).toList());
        // Explicit node→tool listing eliminates the LLM's off-by-one indexing failure mode.
        // Without this, models routinely emit tool_0 keys (0-indexed) when the suggestion
        // service uses tool_1, tool_2, … (1-indexed), and every node ends up with the next
        // tool's argMappings — Get_OrderID without ORD_ID, Serviceability with iterator args, etc.
        userPayload.put("nodeIdsByTool", buildNodeListing(calls));
        if (!skillContexts.isEmpty()) {
            userPayload.put("skillContexts", skillContexts);
        }
        if (corrective != null && !corrective.isBlank()) {
            userPayload.put("correctiveFeedback", corrective);
        }

        try {
            ChatClient client = modelProviderRouter.resolve(modelRef, true).client();
            String raw = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(toJson(userPayload))
                    .call()
                    .content();
            String jsonBlock = extractJsonObject(raw);
            if (jsonBlock == null || jsonBlock.isBlank()) {
                log.warn("[ToolChainAuthoringService] LLM returned no JSON block; raw={}", truncate(raw, 400));
                return Optional.empty();
            }
            Map<String, Object> parsed = objectMapper.readValue(jsonBlock, Map.class);
            return Optional.of(buildResult(parsed));
        } catch (Exception e) {
            log.warn("[ToolChainAuthoringService] Authoring call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private AuthoringResult buildResult(Map<String, Object> parsed) {
        String name = stringOrNull(parsed.get("name"));
        String description = stringOrNull(parsed.get("description"));
        List<String> intents = readStringList(parsed.get("intents"));
        Map<String, Map<String, Object>> nodeMappings = readNodeMappings(parsed.get("nodeMappings"));
        Map<String, Object> paramSchema = parsed.get("paramSchema") instanceof Map<?, ?> ps
                ? (Map<String, Object>) ps : null;
        String paramExtractionHints = stringOrNull(parsed.get("paramExtractionHints"));
        return new AuthoringResult(name, description, intents, nodeMappings, paramSchema, paramExtractionHints);
    }

    /**
     * Walk the recorded turn for `skill` tool.call/tool.done pairs and capture the
     * loaded skill markdown. Returned as [{skillName, content}], one entry per skill
     * loaded during the turn. Output is truncated per skill to stay inside the LLM's
     * context window.
     */
    private List<Map<String, Object>> extractSkillContexts(List<RuntimeEvent> events) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        Map<String, String> nameByCallId = new LinkedHashMap<>();
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            String type = event.getEventType().toLowerCase(Locale.ROOT);
            Map<String, Object> payload = readMap(event.getPayload());
            String toolName = stringOrNull(payload.get("toolName"));
            if (toolName == null || !"skill".equalsIgnoreCase(toolName)) continue;
            String callId = stringOrNull(payload.get("callId"));
            if (callId == null) continue;
            if ("tool.call".equals(type)) {
                Object input = parseFlexible(payload.get("input"));
                String skillName = "";
                if (input instanceof Map<?, ?> m) {
                    Object n = m.get("name");
                    if (n != null) skillName = String.valueOf(n);
                }
                nameByCallId.put(callId, skillName);
            } else if ("tool.done".equals(type)) {
                String status = stringOrNull(payload.get("status"));
                if ("error".equalsIgnoreCase(status) || "denied".equalsIgnoreCase(status)) continue;
                Object output = payload.get("output");
                if (output == null) continue;
                String content = output instanceof String s ? s : output.toString();
                if (content.length() > MAX_SKILL_CONTENT_CHARS) {
                    content = content.substring(0, MAX_SKILL_CONTENT_CHARS) + "…[truncated]";
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("skillName", nameByCallId.getOrDefault(callId, ""));
                entry.put("content", content);
                contexts.add(entry);
            }
        }
        return contexts;
    }

    private static final int MAX_SKILL_CONTENT_CHARS = 8000;

    /**
     * Mirrors {@code ToolChainSuggestionService.groupConsecutiveSameTool} + {@code buildGraph}
     * so the LLM receives the EXACT node IDs it must use in nodeMappings keys. Without this
     * the LLM tends to invent its own indexing (tool_0..N-1) and the off-by-one shifts every
     * mapping by one position.
     */
    private List<Map<String, Object>> buildNodeListing(List<RecordedToolCall> calls) {
        List<Map<String, Object>> listing = new ArrayList<>();
        int nodeIndex = 0;
        int i = 0;
        while (i < calls.size()) {
            String toolName = calls.get(i).toolName();
            int j = i;
            while (j < calls.size() && toolName.equals(calls.get(j).toolName())) j++;
            int groupSize = j - i;
            nodeIndex++;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", "tool_" + nodeIndex);
            entry.put("toolName", toolName);
            entry.put("nodeType", groupSize > 1 ? "iterator" : "tool");
            if (groupSize > 1) entry.put("recordedSampleCount", groupSize);
            listing.add(entry);
            i = j;
        }
        return listing;
    }

    private List<RecordedToolCall> pairCallsAndResults(List<RuntimeEvent> events) {
        List<RecordedToolCall> calls = new ArrayList<>();
        Map<String, RecordedToolCall> byCallId = new LinkedHashMap<>();
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String callId = stringOrNull(payload.get("callId"));
            String toolName = stringOrNull(payload.get("toolName"));
            String type = event.getEventType().toLowerCase(Locale.ROOT);
            if ("tool.call".equals(type)) {
                if (callId == null || toolName == null || toolName.isBlank()) continue;
                if (AGENT_LOOP_INFRASTRUCTURE_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) continue;
                Object input = parseFlexible(payload.get("input"));
                RecordedToolCall call = new RecordedToolCall(toolName, input, null);
                byCallId.put(callId, call);
                calls.add(call);
            } else if ("tool.done".equals(type)) {
                if (callId == null) continue;
                RecordedToolCall existing = byCallId.get(callId);
                if (existing == null) continue;
                Object output = parseFlexible(payload.get("output"));
                String status = stringOrNull(payload.get("status"));
                int idx = calls.indexOf(existing);
                if (idx >= 0) {
                    calls.set(idx, new RecordedToolCall(existing.toolName(), existing.input(), output));
                    byCallId.put(callId, calls.get(idx));
                }
                if ("error".equalsIgnoreCase(status) || "denied".equalsIgnoreCase(status)) {
                    return List.of();
                }
            }
        }
        // Drop calls without a matching result (incomplete turns are not safe to cache)
        List<RecordedToolCall> complete = new ArrayList<>();
        for (RecordedToolCall call : calls) {
            if (call.output() != null) complete.add(call);
        }
        return complete;
    }

    private Map<String, Object> renderCall(RecordedToolCall call) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("toolName", call.toolName());
        rendered.put("input", call.input());
        rendered.put("output", truncateForPrompt(call.output()));
        AgentTool tool = toolRegistryService.getEnabledToolByName(call.toolName());
        if (tool != null && tool.getRequestSchema() != null && !tool.getRequestSchema().isBlank()) {
            try {
                rendered.put("requestSchema", objectMapper.readValue(tool.getRequestSchema(), Object.class));
            } catch (Exception ignored) {
                rendered.put("requestSchema", tool.getRequestSchema());
            }
        }
        return rendered;
    }

    private Object truncateForPrompt(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() <= MAX_TOOL_OUTPUT_CHARS) return value;
            return Map.of(
                    "_truncated", true,
                    "_originalLength", json.length(),
                    "preview", json.substring(0, MAX_TOOL_OUTPUT_CHARS)
            );
        } catch (Exception e) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readNodeMappings(Object raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> outer)) return out;
        for (Map.Entry<?, ?> nodeEntry : outer.entrySet()) {
            String nodeId = String.valueOf(nodeEntry.getKey());
            if (!(nodeEntry.getValue() instanceof Map<?, ?> args)) continue;
            Map<String, Object> argMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> argEntry : args.entrySet()) {
                String argName = String.valueOf(argEntry.getKey());
                if (argName == null || argName.isBlank()) continue;
                argMap.put(argName, argEntry.getValue());
            }
            if (!argMap.isEmpty()) out.put(nodeId, argMap);
        }
        return out;
    }

    private List<String> readStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                String s = String.valueOf(item).trim();
                if (!s.isBlank()) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        Matcher fenced = FENCED_JSON.matcher(text);
        if (fenced.find()) {
            text = fenced.group(1).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private Object parseFlexible(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) return raw;
        if (s.isBlank()) return s;
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception ignored) {
            return s;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static final String SYSTEM_PROMPT = """
            You design a reusable workflow ("toolchain") from one recorded execution.
            You will receive: the user's original prompt, an ordered list of nodes (each either
            a single tool call or an iterator group), and OPTIONALLY the markdown content of
            any skills the recorded turn loaded.

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            CRITICAL — NODE INDEXING. READ THIS BEFORE WRITING ANY EXPRESSION.

            The input includes a `nodeIdsByTool` array. Those IDs are the ONLY valid node IDs.
            They are 1-indexed: the FIRST tool is tool_1. There is NO tool_0. Ever.

            Two rules — both mandatory:

            1) Keys in your `nodeMappings` MUST be exactly one of the IDs from `nodeIdsByTool`.
               If nodeIdsByTool says `[{nodeId:"tool_1", toolName:"Get_OrderID"}, …]`, then
               your nodeMappings must have a key `"tool_1"` whose argMappings define args FOR
               Get_OrderID — not for Serviceability, not for the next tool. The mapping at
               key `tool_1` IS the arg-resolution recipe for the tool whose nodeId is tool_1.

            2) JSONata expressions reference prior steps as `$.tool_<N>.output.<…>` where N is
               the nodeId of the EARLIER step you want output from. Get_OrderID's output is at
               `$.tool_1.output.…`, not `$.tool_0.output.…`. Writing `$.tool_0` always returns
               null and silently breaks the chain.

            Self-check before returning your JSON: for every nodeMapping key, look it up in
            nodeIdsByTool. If it isn't there, fix it. For every $.tool_<N> reference in any
            expression, the same: it must match a nodeId from the listing.
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            If the input includes a `correctiveFeedback` field, your previous attempt at this
            chain failed validation. Each failed mapping is listed with expected vs. resolved
            value. Fix EVERY listed failure in your new output — do not regress on the rest.

            HIGHEST-PRIORITY INPUT: skillContexts
            If the input includes a `skillContexts` array, that markdown is the authoritative
            rules sheet for this workflow. It contains lookup tables, decision logic, address
            filtering rules, and field-mapping rules that are NOT derivable from JSON Schema or
            sample I/O alone. Mine these rules and translate them into JSONata expressions for
            argMappings. Examples of what skill markdown typically encodes:
              - "IDEL → ID" lookups: produce a chained ternary or a $lookup() call
              - "Filter Lines where ServiceCode in [list] AND ScheduledDate is not null":
                fold every condition into the JSONata predicate
              - "Use IsCustomerAddress=true": include this filter in every address lookup
              - Decision tables with N>2 cases (e.g. journey types): write a chained ternary
                that covers EVERY case the markdown lists, or set policy:"llm_assisted" with
                the table content as a hint
            When skill rules exist, your inferred mappings MUST match them exactly.

            CRITICAL: You must declare the chain's typed input parameters. The chat layer will
            run a separate small LLM call to extract these params from a user's message before
            invoking the chain. Tool argMappings then reference $.chainInput.<paramName> directly
            — NOT $.chainInput.message. This is the whole point of the chain: free-form prose
            stops at the chain entry, and structured params flow through deterministically.

            Return ONLY valid JSON, no prose, no code fences:
            {
              "name": "<2-8 word business-purpose name, no implementation jargon>",
              "description": "<one or two sentences describing what the chain does>",
              "intents": ["<paraphrase of likely user prompts that should match this chain>"],
              "paramSchema": {
                "type": "object",
                "properties": {
                  "<paramName>": { "type": "string|integer|number|boolean|array|object", "description": "<what this param represents>" }
                },
                "required": ["<paramName>", ...]
              },
              "paramExtractionHints": "<one short sentence with concrete examples mapping a likely user phrase to the param object>",
              "nodeMappings": {
                "tool_<N>": {
                  "<argName>": { "expr": "<JSONata>", "policy": "strict" | "llm_assisted", "fallback": <optional> }
                }
              }
              // For iterator nodes (you'll see them in the input as type: "iterator" with
              // "recordedInputSamples" showing per-iteration shapes), key by node id and add
              // a special "items" entry alongside the per-iteration arg mappings:
              //
              //   "tool_3": {
              //     "items": { "expr": "$.tool_1.output.Lines[ServiceCode in ['IDEL','WTW','RDL','FPU']]" },
              //     "referenceDate": { "expr": "$item.ScheduledDate", "policy": "strict" },
              //     "siteIdentity":  { "expr": "$item.AssignedSiteId",  "policy": "strict" }
              //   }

            Rules for paramSchema:
            - Look at the FIRST tool's input in the recorded run AND the user's prompt. The "real"
              params are the structured values the user implicitly provided.
              Example: prompt "validate order 5038081" + first tool input {ORD_ID: "5038081"} -->
              paramSchema: { type:object, properties:{orderId:{type:string,description:"order ID"}}, required:["orderId"] }
            - Use camelCase param names (orderId, customerId, postalCode), even if the underlying
              tools use other casing. Tool argMappings handle the rename.
            - Required = every param the chain genuinely needs to function. Optional params go in
              properties without being listed in required.
            - Do not add a "message" param. The chain receives typed params, not free-form prose.
            }

            Rules for argMappings (single-tool nodes):
            - The runtime context exposes:
                $.chainInput.<paramName>      — the typed params extracted at chain entry (per paramSchema)
                $.tool_<N>.input.<key>        — the resolved arguments sent to step N
                $.tool_<N>.output.<key>       — the response from step N
            - Prefer $.chainInput.<paramName> over any other source for top-level inputs.
              Example: ORD_ID for Get_OrderID -> { "expr": "$.chainInput.orderId", "policy": "strict" }

            JSONata gotchas you MUST account for (these cause silent runtime failures):
            1) Predicates return arrays, NOT scalars.
               WRONG: `$.tool_1.output.Lines[ServiceCode='WRT'].Addresses[IsCustomerAddress=true].PostalCode`
                      — this returns ["89011"] (an array), and a tool expecting `zip: string` rejects it.
               RIGHT: `$.tool_1.output.Lines[ServiceCode='WRT'][0].Addresses[IsCustomerAddress=true][0].PostalCode`
                      — uses `[0]` after each predicate to get the scalar.
               When the schema for an arg is type:string|integer|number|boolean, ALWAYS wrap
               every predicate-walk with `[0]` (or use `$first(...)`).

            2) "First match" needs to be intentional.
               If the recorded turn shows the tool was called with the customer's zip (89011) and
               not the storage center's zip (89030), the JSONata MUST filter to the customer
               address, not just take Addresses[0]. Look at the recorded input to identify which
               specific predicate (IsCustomerAddress=true, AddressType='Origination', etc.)
               narrows to the right value.

            3) Some PODS-style data has a canonical code in ItemCode and a sequence-friendly
               variant in ServiceCode. For an "IDEL" leg, the row has ItemCode='IDEL' and
               ServiceCode='NEW'. A filter `Lines[ServiceCode='IDEL']` matches ZERO rows.
               WRONG: `Lines[ServiceCode in ['IDEL','WRT','WTW','RDL','FPU']]`
               RIGHT: `Lines[(ServiceCode in ['IDEL','WRT','WTW','RDL','FPU']) or (ItemCode in ['IDEL','WRT','WTW','RDL','FPU'])]`
               Only use this dual-filter pattern when you've inspected the recorded data and
               confirmed the canonical code lives in ItemCode for some rows.

            4) For iterator `items`: ALWAYS exclude rows where required-by-the-tool fields are null.
               The tool's request schema lists required fields; for each one whose source field
               can be null in the data, add an `X != null` clause to the predicate.
               Example for ContainerAvailability (requires referenceDate):
                 `Lines[(ServiceCode in [...]) and ScheduledDate != null]`

            5) For decision trees with N>2 outcomes (e.g. journeyType with 11 cases from a
               decision-table-style rule sheet), do NOT collapse to a binary ternary. Either:
               (a) write a chained ternary that covers EVERY documented case, or
               (b) set policy:"llm_assisted" and put the rule sheet text in a comment-style
                   "hint" key so the runtime LLM has the rules at fallback time.
               Never silently drop documented cases — that produces wrong-but-plausible output.

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            llm_assisted IS NOT A "GIVE UP" BUTTON.

            policy:"llm_assisted" without an expr is INVALID. Never emit it. The runtime
            LLM resolver may not be reachable, may return null, and even when it works it
            costs a per-arg LLM call on every chain execution until self-heal kicks in.

            Mandatory checklist before using llm_assisted on any arg:
              a. The recorded value is NOT a constant string/number/bool. Constants
                 (like tableName="Leg Sequences" for every run) MUST be authored as
                 {"expr": "'Leg Sequences'", "policy": "strict"} — never llm_assisted.
              b. The recorded value is NOT a direct field of an upstream output. Direct
                 lookups (custTrackingId from chainInput.orderId, zip from a leg's address)
                 MUST be authored as JSONata with policy:"strict".
              c. You have actually attempted a JSONata expression and the value genuinely
                 requires runtime reasoning (e.g. an 11-case decision tree with rules in
                 the skill markdown).

            If (c) is true, you STILL provide your best-guess JSONata in `expr` so the
            verify-and-self-heal path can promote to strict over time. {policy:"llm_assisted",
            expr: <your best attempt>} is OK. {policy:"llm_assisted"} alone is never OK.

            Most importantly: cover EVERY arg that appears in the recorded tool input. If
            the recorded call shows {tableName, inputs}, your nodeMappings must have BOTH
            tableName and inputs. Missing one means the chain calls the tool without that
            field and the tool rejects the request — same effect as authoring nothing at all.
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            - Use JSONata expressions starting with "$". For static literals, use a literal expression
              like "'WEB'" (quoted string) or "1" (number) or "false".
            - When a value can be derived deterministically from prior outputs/inputs, set
              "policy": "strict".
            - When a value requires multi-rule reasoning, lookup tables, or judgement that cannot be
              cleanly expressed in JSONata, set "policy": "llm_assisted" and still provide your best
              JSONata as a hint (it will be tried first, the LLM is only invoked as fallback).
            - Cover EVERY required argument from the tool's request JSON Schema. Do not skip required args.
            - Do not hallucinate fields that are not present in the recorded outputs.

            Rules for iterator nodes (when the recorded run shows the same tool called multiple times):
            - Set the special "items" key to a JSONata expression that produces a list to iterate over.
              Example: "$.tool_1.output.Lines[ServiceCode in ['IDEL','WTW','RDL','FPU']]".
            - Within the iterator's argMappings, reference the current iteration's data via "$item.X"
              and any other context as usual ("$.tool_1.output...", "$.chainInput...").
            - If the iteration cannot be derived from a single JSONata expression (e.g., the items come
              from multiple sources), set "items": { "expr": "$item", "policy": "llm_assisted" } and the
              runtime will resolve them via LLM.

            Naming rules:
            - Name: 2-8 words, business outcome (not "Step 1 then Step 2"). Title Case. No quotes.
            - Description: factual, present tense, 1-2 sentences. No marketing language.
            - Intents: 2-5 entries. Examples: "validate order {orderId}", "check order availability".

            If the recorded run has fewer than 2 tool calls, errored, or is too partial to generalize,
            return an empty JSON object {} and nothing else.
            """;

    public record AuthoringResult(String name,
                                  String description,
                                  List<String> intents,
                                  Map<String, Map<String, Object>> nodeMappings,
                                  Map<String, Object> paramSchema,
                                  String paramExtractionHints) {
        public boolean hasName() { return name != null && !name.isBlank(); }
        public boolean hasDescription() { return description != null && !description.isBlank(); }
        public boolean hasParamSchema() {
            return paramSchema != null && paramSchema.get("properties") instanceof Map<?, ?> p && !p.isEmpty();
        }
    }

    public record RecordedToolCall(String toolName, Object input, Object output) {}
}
