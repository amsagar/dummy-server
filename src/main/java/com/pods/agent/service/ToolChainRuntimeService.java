package com.pods.agent.service;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.tool.SkillExecutionGate;
import com.pods.agent.agent.tool.SkillToolCallback;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChainApproval;
import com.pods.agent.domain.ToolChainRun;
import com.pods.agent.domain.ToolChainRunStep;
import com.pods.agent.domain.ToolChainVersion;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.ToolChainApprovalRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainRunStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ToolChainRuntimeService {
    private static final Set<String> ALLOWED_TRIGGER_SOURCES = Set.of("chat", "api", "manual", "rerun");
    private final ToolChainService toolChainService;
    private final ToolRegistryService toolRegistryService;
    private final ToolExecutionService toolExecutionService;
    private final ToolChainRunRepository runRepository;
    private final ToolChainRunStepRepository runStepRepository;
    private final ToolChainApprovalRepository approvalRepository;
    private final RuntimeEventRepository runtimeEventRepository;
    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final DecisionTableService decisionTableService;
    private final ObjectMapper objectMapper;
    private final ExecutorService runExecutor = Executors.newFixedThreadPool(6);
    private final ExecutorService branchExecutor = Executors.newFixedThreadPool(8);
    private final ConcurrentHashMap<String, CompletableFuture<ApprovalDecision>> pendingApprovals = new ConcurrentHashMap<>();

    public ToolChainRuntimeService(ToolChainService toolChainService,
                                   ToolRegistryService toolRegistryService,
                                   ToolExecutionService toolExecutionService,
                                   ToolChainRunRepository runRepository,
                                   ToolChainRunStepRepository runStepRepository,
                                   ToolChainApprovalRepository approvalRepository,
                                   RuntimeEventRepository runtimeEventRepository,
                                   ModelProviderRouter modelProviderRouter,
                                   SkillRegistryService skillRegistryService,
                                   RuntimeTuningProperties runtimeTuningProperties,
                                   DecisionTableService decisionTableService,
                                   ObjectMapper objectMapper) {
        this.toolChainService = toolChainService;
        this.toolRegistryService = toolRegistryService;
        this.toolExecutionService = toolExecutionService;
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.approvalRepository = approvalRepository;
        this.runtimeEventRepository = runtimeEventRepository;
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.decisionTableService = decisionTableService;
        this.objectMapper = objectMapper;
    }

    public ToolChainRun execute(String toolChainId,
                                Integer requestedVersion,
                                String triggerSource,
                                String initiatedBy,
                                Map<String, Object> input,
                                Map<String, Object> options,
                                SseEventSender sender) {
        return execute(toolChainId, requestedVersion, triggerSource, initiatedBy, input, options, sender, null);
    }

    /**
     * @param chatSessionId  if non-null, every SSE event the run emits is tagged with this
     *                       sessionId instead of the run's id, so events flow into the
     *                       caller's chat session and the chat UI's session filter accepts
     *                       them. The runId stays in event payloads for run-detail correlation.
     */
    public ToolChainRun execute(String toolChainId,
                                Integer requestedVersion,
                                String triggerSource,
                                String initiatedBy,
                                Map<String, Object> input,
                                Map<String, Object> options,
                                SseEventSender sender,
                                String chatSessionId) {
        var chain = toolChainService.getRequired(toolChainId);
        if (ToolChainService.ORIGIN_SYSTEM_SUGGESTED.equalsIgnoreCase(chain.getOrigin())
                && !ToolChainService.APPROVAL_APPROVED.equalsIgnoreCase(chain.getApprovalStatus())) {
            throw new IllegalStateException("System suggested ToolChain requires one-time approval before it can run.");
        }
        ToolChainVersion version = toolChainService.resolveVersion(toolChainId, requestedVersion)
                .orElseThrow(() -> new IllegalArgumentException("No ToolChain version is available for " + toolChainId));
        Map<String, Object> normalizedInput = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        validateInputAgainstSchema(version, normalizedInput);
        ToolChainRun run = runRepository.save(ToolChainRun.builder()
                .toolChainId(toolChainId)
                .toolChainVersionId(version.getId())
                .version(version.getVersion())
                .triggerSource(normalizeTriggerSource(triggerSource))
                .initiatedBy(initiatedBy)
                .status("queued")
                .inputSnapshot(toJson(normalizedInput))
                .build());
        boolean async = options != null && Boolean.TRUE.equals(options.get("async"));
        Map<String, Object> safeOptions = options == null ? Map.of() : options;
        String effectiveSessionId = chatSessionId != null && !chatSessionId.isBlank() ? chatSessionId : run.getId();
        if (async) {
            runExecutor.submit(() -> executeInternal(run.getId(), version, new LinkedHashMap<>(normalizedInput), safeOptions, sender, effectiveSessionId));
            run.setStatus("running");
            return run;
        }
        return executeInternal(run.getId(), version, new LinkedHashMap<>(normalizedInput), safeOptions, sender, effectiveSessionId);
    }

    public Optional<ToolChainRun> getRun(String runId) {
        return runRepository.findById(runId);
    }

    public List<ToolChainRunStep> getRunSteps(String runId) {
        return runStepRepository.findByRun(runId);
    }

    public List<ToolChainApproval> getRunApprovals(String runId) {
        return approvalRepository.findByRun(runId);
    }

    public List<ToolChainApproval> getPendingApprovals() {
        return approvalRepository.findPending();
    }

    public List<RuntimeEvent> getRunEvents(String runId) {
        return runtimeEventRepository.findByTurnId(runId);
    }

    public List<RuntimeEvent> pollRunEvents(String runId, long afterCreatedAt, int limit) {
        return runtimeEventRepository.findByTurnIdAfter(runId, afterCreatedAt, limit);
    }

    public boolean isTerminalStatus(String status) {
        if (status == null) return false;
        return Set.of("success", "failed", "rejected", "cancelled").contains(status.toLowerCase(Locale.ROOT));
    }

    public void approve(String requestId, String approver, String comment) {
        approvalRepository.decide(requestId, "approved", approver, comment);
        CompletableFuture<ApprovalDecision> future = pendingApprovals.remove(requestId);
        if (future != null) future.complete(new ApprovalDecision(true, comment));
    }

    public void reject(String requestId, String approver, String comment) {
        approvalRepository.decide(requestId, "rejected", approver, comment);
        CompletableFuture<ApprovalDecision> future = pendingApprovals.remove(requestId);
        if (future != null) future.complete(new ApprovalDecision(false, comment));
    }

    public ToolChainRun rerun(String runId, String initiatedBy) {
        ToolChainRun original = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        Map<String, Object> input = fromJsonMap(original.getInputSnapshot());
        return execute(original.getToolChainId(), original.getVersion(), "rerun", initiatedBy, input, Map.of("async", true), null, null);
    }

    private ToolChainRun executeInternal(String runId,
                                         ToolChainVersion version,
                                         Map<String, Object> input,
                                         Map<String, Object> options,
                                         SseEventSender sender,
                                         String effectiveSessionId) {
        ToolChainRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        runRepository.updateStatus(runId, "running", null, null);
        GraphModel graph = parseGraph(version.getGraphJson());
        Map<String, NodeModel> nodes = graph.nodesById();
        Map<String, List<String>> outgoing = graph.outgoing();
        Map<String, Integer> indegree = graph.indegree();
        Map<String, Object> context = new LinkedHashMap<>(input);
        Queue<String> ready = new ArrayDeque<>();
        indegree.forEach((k, v) -> {
            if (v == 0) ready.offer(k);
        });

        String finalStatus = "success";
        while (!ready.isEmpty()) {
            List<String> batch = new ArrayList<>();
            while (!ready.isEmpty()) batch.add(ready.poll());
            List<CompletableFuture<StepResult>> futures = batch.stream()
                    .map(nodeId -> CompletableFuture.supplyAsync(() -> executeNode(runId, nodes.get(nodeId), context, sender, effectiveSessionId, options), branchExecutor))
                    .toList();
            for (int i = 0; i < batch.size(); i++) {
                String nodeId = batch.get(i);
                StepResult result = futures.get(i).join();
                if (!result.success()) {
                    finalStatus = result.rejected() ? "rejected" : "failed";
                    completeRun(runId, finalStatus, context, result.error(), effectiveSessionId);
                    return runRepository.findById(runId).orElse(run);
                }
                if (result.outputs() != null) context.putAll(result.outputs());

                List<String> next = outgoing.getOrDefault(nodeId, List.of());
                if (result.nextNodes() != null && !result.nextNodes().isEmpty()) {
                    next = result.nextNodes();
                }
                for (String to : next) {
                    indegree.put(to, indegree.getOrDefault(to, 1) - 1);
                    if (indegree.get(to) <= 0) ready.offer(to);
                }
            }
        }

        String output = finalizeResponse(run.getToolChainId(), version, context, runId, options, sender, effectiveSessionId);
        completeRun(runId, "success", Map.of("result", output, "context", context), null, effectiveSessionId);
        return runRepository.findById(runId).orElse(run);
    }

    private StepResult executeNode(String runId, NodeModel node, Map<String, Object> context, SseEventSender sender, String effectiveSessionId, Map<String, Object> runOptions) {
        if (node == null) return StepResult.failed("Missing node");

        int maxAttempts = retryAttempts(node);
        long backoff = retryBackoffMs(node);
        double multiplier = retryMultiplier(node);
        long maxBackoff = retryMaxBackoffMs(node);

        StepResult result = null;
        int attempt = 0;
        for (; attempt <= maxAttempts; attempt++) {
            result = executeNodeAttempt(runId, node, context, sender, effectiveSessionId, attempt, maxAttempts, runOptions);
            if (result.success() || result.rejected()) break;
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(Math.min(backoff, maxBackoff));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = (long) Math.min(backoff * multiplier, maxBackoff);
            }
        }

        if (result != null && !result.success() && !result.rejected()) {
            String onError = node.configString("onError");
            boolean continueOnFail = Boolean.TRUE.equals(node.config().get("continueOnFail"));
            if (onError != null && !onError.isBlank()) {
                Map<String, Object> recovered = new LinkedHashMap<>();
                recovered.put("error", result.error());
                recovered.put("recovered", true);
                recovered.put("attempts", attempt);
                return StepResult.success(Map.of(node.id(), recovered), List.of(onError));
            }
            if (continueOnFail) {
                Map<String, Object> errOut = new LinkedHashMap<>();
                errOut.put("error", result.error());
                errOut.put("attempts", attempt);
                return StepResult.success(Map.of(node.id(), errOut), null);
            }
        }
        return result;
    }

    private StepResult executeNodeAttempt(String runId, NodeModel node, Map<String, Object> context, SseEventSender sender, String effectiveSessionId, int attempt, int maxAttempts, Map<String, Object> runOptions) {
        ToolChainRunStep step = runStepRepository.save(ToolChainRunStep.builder()
                .runId(runId)
                .nodeId(node.id())
                .nodeType(node.type())
                .toolRef(node.toolRef())
                .status("running")
                .inputPayload(toJson(context))
                .startedAt(System.currentTimeMillis())
                .build());
        Map<String, Object> startedPayload = new LinkedHashMap<>();
        startedPayload.put("sessionId", effectiveSessionId);
        startedPayload.put("stepId", step.getId());
        startedPayload.put("taskId", node.id());
        startedPayload.put("taskName", node.label() == null ? node.id() : node.label());
        startedPayload.put("type", node.type());
        startedPayload.put("toolRef", node.toolRef() == null ? "" : node.toolRef());
        startedPayload.put("input", context);
        if (maxAttempts > 0) {
            startedPayload.put("attempt", attempt);
            startedPayload.put("maxAttempts", maxAttempts);
        }
        saveRuntimeEvent(runId, "task.started", startedPayload, effectiveSessionId);
        if (sender != null) sender.sendCustom("task.started", startedPayload);

        try {
            if (requiresApproval(node)) {
                StepResult approval = handleApproval(runId, node, step, sender, effectiveSessionId);
                if (!approval.success()) {
                    return approval;
                }
            }

            StepResult result = switch (node.type().toLowerCase(Locale.ROOT)) {
                case "start", "end" -> StepResult.success(Map.of(node.id(), "ok"), null);
                case "decision" -> executeDecision(node, context);
                case "switch" -> executeSwitch(node, context);
                case "merge" -> executeMerge(node, context);
                case "wait" -> executeWait(node);
                case "subchain" -> executeSubchain(node, context, runId, sender, effectiveSessionId, runOptions);
                case "iterator" -> executeIterator(node, context, runId, sender, effectiveSessionId, runOptions);
                case "tool", "mcp_tool" -> executeToolNode(node, context);
                case "decision_table" -> executeDecisionTableNode(node, context);
                case "synthesis" -> executeSynthesisNode(node, context, runId, sender, effectiveSessionId);
                case "skill" -> StepResult.failed("Skill nodes are no longer supported as graph steps; use synthesisPrompt to invoke skills in the final LLM stage.");
                default -> StepResult.success(Map.of(node.id(), "skipped"), null);
            };
            step.setStatus(result.success() ? "success" : (result.rejected() ? "rejected" : "failed"));
            step.setOutputPayload(toJson(result.outputs()));
            step.setErrorMessage(result.error());
            step.setEndedAt(System.currentTimeMillis());
            runStepRepository.update(step);
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sessionId", effectiveSessionId);
            donePayload.put("stepId", step.getId());
            donePayload.put("taskId", node.id());
            donePayload.put("taskName", node.label() == null ? node.id() : node.label());
            donePayload.put("result", step.getStatus());
            donePayload.put("output", result.outputs() == null ? Map.of() : result.outputs());
            donePayload.put("error", step.getErrorMessage() == null ? "" : step.getErrorMessage());
            if (maxAttempts > 0) {
                donePayload.put("attempt", attempt);
                donePayload.put("maxAttempts", maxAttempts);
            }
            saveRuntimeEvent(runId, "task.done", donePayload, effectiveSessionId);
            if (sender != null) sender.sendCustom("task.done", donePayload);
            return result;
        } catch (Exception e) {
            step.setStatus("failed");
            step.setErrorMessage(e.getMessage());
            step.setEndedAt(System.currentTimeMillis());
            runStepRepository.update(step);
            Map<String, Object> failPayload = new LinkedHashMap<>();
            failPayload.put("sessionId", effectiveSessionId);
            failPayload.put("stepId", step.getId());
            failPayload.put("taskId", node.id());
            failPayload.put("taskName", node.label() == null ? node.id() : node.label());
            failPayload.put("result", "failed");
            failPayload.put("error", e.getMessage() == null ? "Step execution failed" : e.getMessage());
            if (maxAttempts > 0) {
                failPayload.put("attempt", attempt);
                failPayload.put("maxAttempts", maxAttempts);
            }
            saveRuntimeEvent(runId, "task.done", failPayload, effectiveSessionId);
            if (sender != null) sender.sendCustom("task.done", failPayload);
            return StepResult.failed(e.getMessage());
        }
    }

    private int retryAttempts(NodeModel node) {
        Map<String, Object> retry = node.configMap("retry");
        Object attempts = retry.get("attempts");
        if (attempts instanceof Number n) return Math.max(0, Math.min(n.intValue(), 10));
        return 0;
    }

    private long retryBackoffMs(NodeModel node) {
        Map<String, Object> retry = node.configMap("retry");
        Object backoff = retry.get("backoffMs");
        if (backoff instanceof Number n) return Math.max(0L, n.longValue());
        return 1000L;
    }

    private double retryMultiplier(NodeModel node) {
        Map<String, Object> retry = node.configMap("retry");
        Object mult = retry.get("multiplier");
        if (mult instanceof Number n) {
            double v = n.doubleValue();
            return v < 1.0 ? 1.0 : Math.min(v, 10.0);
        }
        return 1.0;
    }

    private long retryMaxBackoffMs(NodeModel node) {
        Map<String, Object> retry = node.configMap("retry");
        Object cap = retry.get("maxBackoffMs");
        if (cap instanceof Number n) return Math.max(100L, n.longValue());
        return 30_000L;
    }

    private StepResult handleApproval(String runId, NodeModel node, ToolChainRunStep step, SseEventSender sender, String effectiveSessionId) {
        String requestId = UUID.randomUUID().toString();
        approvalRepository.save(ToolChainApproval.builder()
                .runId(runId)
                .stepId(step.getId())
                .nodeId(node.id())
                .requestId(requestId)
                .approvalGroup(node.approvalGroup())
                .prompt(node.approvalPrompt() == null ? "Approval required for " + node.label() : node.approvalPrompt())
                .status("pending")
                .createdAt(System.currentTimeMillis())
                .build());
        runRepository.updateStatus(runId, "waiting_for_approval", null, null);
        step.setStatus("waiting_for_approval");
        step.setEndedAt(System.currentTimeMillis());
        runStepRepository.update(step);
        if (sender != null) sender.sendApprovalRequired(effectiveSessionId, requestId, node.label());
        saveRuntimeEvent(runId, "toolchain.approval.required", Map.of("nodeId", node.id(), "requestId", requestId), effectiveSessionId);

        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pendingApprovals.put(requestId, future);
        long timeoutMs = node.approvalTimeoutMs();
        try {
            ApprovalDecision decision = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!decision.approved()) {
                return StepResult.rejected(decision.comment() == null ? "Step rejected" : decision.comment());
            }
            return StepResult.success(Map.of("approval." + node.id(), "approved"), null);
        } catch (Exception e) {
            approvalRepository.decide(requestId, "timeout", null, "Approval timed out");
            pendingApprovals.remove(requestId);
            return StepResult.failed("Approval timed out");
        } finally {
            if (runRepository.findById(runId).map(r -> "waiting_for_approval".equalsIgnoreCase(r.getStatus())).orElse(false)) {
                runRepository.updateStatus(runId, "running", null, null);
            }
        }
    }

    private StepResult executeToolNode(NodeModel node, Map<String, Object> context) {
        String toolName = node.toolRef();
        if (toolName == null || toolName.isBlank()) {
            return StepResult.failed("Tool node is missing tool reference");
        }
        AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
        if (tool == null) {
            return StepResult.failed("Tool is not enabled: " + toolName);
        }
        String payload = toJson(resolveNodeInput(node, context));
        ToolExecutionService.ExecutionResult result = toolExecutionService.execute(tool, payload);
        if (!result.success()) {
            return StepResult.failed(result.error());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(node.id(), parseFlexibleBody(result.body()));
        return StepResult.success(out, null);
    }

    private StepResult executeDecisionTableNode(NodeModel node, Map<String, Object> context) {
        String tableName = node.configString("tableName");
        if (tableName == null || tableName.isBlank()) {
            return StepResult.failed("decision_table node is missing tableName");
        }
        Map<String, Object> payload = resolveNodeInput(node, context);
        payload.remove("tableName");
        Map<String, Object> inputs = normalizeDecisionTableInputs(tableName, payload, context);
        List<String> missing = missingRequiredDecisionTableInputs(tableName, inputs);
        if (!missing.isEmpty()) {
            return StepResult.failed("Decision table '" + tableName + "' is missing required inputs: " + String.join(", ", missing));
        }
        try {
            var result = decisionTableService.evaluate(tableName, inputs);
            return StepResult.success(Map.of(node.id(), result.asMap()), null);
        } catch (Exception e) {
            return StepResult.failed("Decision table '" + tableName + "' failed: " + e.getMessage());
        }
    }

    private Map<String, Object> normalizeDecisionTableInputs(String tableName,
                                                             Map<String, Object> payload,
                                                             Map<String, Object> context) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (payload != null) {
            Object nested = payload.get("inputs");
            if (nested instanceof Map<?, ?> nestedMap) {
                for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                    if (entry.getKey() == null) continue;
                    inputs.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String key = entry.getKey();
                if (key == null) continue;
                if ("inputs".equalsIgnoreCase(key) || "tableName".equalsIgnoreCase(key)) continue;
                inputs.putIfAbsent(key, entry.getValue());
            }
        }
        List<String> required = requiredDecisionInputNames(tableName);
        for (String key : required) {
            if (key == null || key.isBlank()) continue;
            Object current = findIgnoreCase(inputs, key);
            if (!isBlankValue(current)) continue;
            Object fromContext = resolvePath(context, key);
            if (fromContext == null && context != null) {
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                        fromContext = entry.getValue();
                        break;
                    }
                }
            }
            if (!isBlankValue(fromContext)) {
                inputs.put(key, fromContext);
            }
        }
        return inputs;
    }

    private List<String> missingRequiredDecisionTableInputs(String tableName, Map<String, Object> inputs) {
        List<String> required = requiredDecisionInputNames(tableName);
        if (required.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (key == null || key.isBlank()) continue;
            Object value = findIgnoreCase(inputs, key);
            if (isBlankValue(value)) missing.add(key);
        }
        return missing;
    }

    private List<String> requiredDecisionInputNames(String tableName) {
        if (decisionTableService == null || tableName == null || tableName.isBlank()) return List.of();
        try {
            List<String> required = decisionTableService.requiredInputNames(tableName);
            return required == null ? List.of() : required;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object findIgnoreCase(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty() || key == null || key.isBlank()) return null;
        if (source.containsKey(key)) return source.get(key);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isBlankValue(Object value) {
        if (value == null) return true;
        String text = String.valueOf(value);
        return text.isBlank();
    }

    private StepResult executeDecision(NodeModel node, Map<String, Object> context) {
        String key = node.configString("sourceKey");
        String equalsValue = node.configString("equals");
        String trueBranch = node.configString("trueBranch");
        String falseBranch = node.configString("falseBranch");
        Object value = context.get(key);
        boolean matched = value != null && value.toString().equalsIgnoreCase(equalsValue);
        List<String> next = matched ? (trueBranch == null ? List.of() : List.of(trueBranch))
                : (falseBranch == null ? List.of() : List.of(falseBranch));
        Map<String, Object> out = Map.of(node.id(), matched ? "true" : "false");
        return StepResult.success(out, next);
    }

    @SuppressWarnings("unchecked")
    private StepResult executeSwitch(NodeModel node, Map<String, Object> context) {
        String sourceKey = node.configString("sourceKey");
        if (sourceKey == null || sourceKey.isBlank()) {
            return StepResult.failed("switch node missing 'sourceKey'");
        }
        Object actual = resolvePath(context, sourceKey);
        String actualStr = actual == null ? "" : String.valueOf(actual);
        Object casesObj = node.config().get("cases");
        String matchedCase = null;
        String target = null;
        if (casesObj instanceof List<?> caseList) {
            for (Object c : caseList) {
                if (!(c instanceof Map<?, ?> caseMap)) continue;
                Object when = caseMap.get("when");
                if (when == null) continue;
                if (String.valueOf(when).equalsIgnoreCase(actualStr)) {
                    matchedCase = String.valueOf(when);
                    Object to = caseMap.get("to");
                    if (to != null) target = String.valueOf(to);
                    break;
                }
            }
        }
        if (target == null) {
            String defaultBranch = node.configString("default");
            if (defaultBranch != null && !defaultBranch.isBlank()) {
                target = defaultBranch;
                matchedCase = "__default__";
            }
        }
        if (target == null) {
            // No match and no default — fall through to outgoing edges as-is.
            return StepResult.success(Map.of(node.id(), Map.of("matched", "none")), null);
        }
        Map<String, Object> out = Map.of(node.id(), Map.of("matched", matchedCase, "branch", target));
        return StepResult.success(out, List.of(target));
    }

    @SuppressWarnings("unchecked")
    private StepResult executeMerge(NodeModel node, Map<String, Object> context) {
        String strategy = node.configString("strategy");
        if (strategy == null || strategy.isBlank()) strategy = "shallow_merge";
        Object sourcesObj = node.config().get("sources");
        if (!(sourcesObj instanceof List<?> sourcesList) || sourcesList.isEmpty()) {
            return StepResult.failed("merge node missing 'sources' list");
        }
        List<Object> values = new ArrayList<>();
        Map<String, Object> kvMap = new LinkedHashMap<>();
        for (Object s : sourcesList) {
            if (s == null) continue;
            String src = String.valueOf(s);
            Object value = resolvePath(context, src);
            kvMap.put(src, value);
            if (value != null) values.add(value);
        }
        Object combined;
        switch (strategy.toLowerCase(Locale.ROOT)) {
            case "concat" -> {
                List<Object> flat = new ArrayList<>();
                for (Object v : values) {
                    if (v instanceof List<?> list) flat.addAll(list);
                    else flat.add(v);
                }
                combined = flat;
            }
            case "first_non_null" -> {
                combined = values.isEmpty() ? null : values.get(0);
            }
            case "pick_object" -> {
                combined = kvMap;
            }
            case "shallow_merge" -> {
                Map<String, Object> merged = new LinkedHashMap<>();
                for (Object v : values) {
                    if (v instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            merged.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                }
                combined = merged;
            }
            default -> {
                return StepResult.failed("merge node unknown strategy: " + strategy);
            }
        }
        return StepResult.success(Map.of(node.id(), combined == null ? Map.of() : combined), null);
    }

    private StepResult executeWait(NodeModel node) {
        Object delayObj = node.config().get("delayMs");
        long delayMs = delayObj instanceof Number n ? n.longValue() : 0L;
        if (delayMs <= 0) {
            return StepResult.success(Map.of(node.id(), Map.of("waited", 0)), null);
        }
        long maxDelay = 10L * 60L * 1000L; // 10 minute hard cap
        long actual = Math.min(delayMs, maxDelay);
        try {
            Thread.sleep(actual);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return StepResult.failed("wait interrupted");
        }
        return StepResult.success(Map.of(node.id(), Map.of("waited", actual)), null);
    }

    private static final int MAX_SUBCHAIN_DEPTH = 5;

    @SuppressWarnings("unchecked")
    private StepResult executeSubchain(NodeModel node, Map<String, Object> context, String parentRunId, SseEventSender sender, String effectiveSessionId, Map<String, Object> runOptions) {
        String subChainId = node.configString("chainId");
        if (subChainId == null || subChainId.isBlank()) {
            return StepResult.failed("subchain node missing 'chainId'");
        }
        Integer subVersion = null;
        Object versionObj = node.config().get("version");
        if (versionObj instanceof Number n) subVersion = n.intValue();

        int currentDepth = 0;
        Object depthObj = runOptions == null ? null : runOptions.get("subchainDepth");
        if (depthObj instanceof Number n) currentDepth = n.intValue();
        if (currentDepth >= MAX_SUBCHAIN_DEPTH) {
            return StepResult.failed("subchain depth limit reached (" + MAX_SUBCHAIN_DEPTH + ") — possible recursion");
        }

        Map<String, Object> resolvedInput = resolveSubchainInput(node, context);
        boolean async = Boolean.TRUE.equals(node.config().get("async"));

        Map<String, Object> childOptions = new LinkedHashMap<>();
        if (runOptions != null) childOptions.putAll(runOptions);
        childOptions.put("subchainDepth", currentDepth + 1);
        childOptions.put("async", async);

        String chatSessionId = childChatSessionId(effectiveSessionId, parentRunId);
        String initiatedBy = inheritedInitiatedBy(parentRunId);

        try {
            ToolChainRun childRun = execute(subChainId, subVersion, "subchain", initiatedBy,
                    resolvedInput, childOptions, sender, chatSessionId);
            Map<String, Object> nodeOut = new LinkedHashMap<>();
            nodeOut.put("subRunId", childRun.getId());
            nodeOut.put("subVersion", childRun.getVersion());
            nodeOut.put("status", childRun.getStatus());
            if (!async) {
                Object resultPayload = parseFlexibleBody(extractResultFromSnapshot(childRun.getOutputSnapshot()));
                nodeOut.put("result", resultPayload);
            }
            return StepResult.success(Map.of(node.id(), nodeOut), null);
        } catch (Exception e) {
            return StepResult.failed("subchain '" + subChainId + "' failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private StepResult executeIterator(NodeModel node, Map<String, Object> context, String parentRunId, SseEventSender sender, String effectiveSessionId, Map<String, Object> runOptions) {
        String over = node.configString("over");
        if (over == null || over.isBlank()) {
            return StepResult.failed("iterator node missing 'over'");
        }
        String as = node.configString("as");
        if (as == null || as.isBlank()) as = "item";
        String subChainId = node.configString("subChainId");
        if (subChainId == null || subChainId.isBlank()) {
            return StepResult.failed("iterator node missing 'subChainId'");
        }
        Integer subVersion = null;
        Object versionObj = node.config().get("subVersion");
        if (versionObj instanceof Number n) subVersion = n.intValue();
        boolean parallel = !Boolean.FALSE.equals(node.config().get("parallel"));
        int maxConcurrency = 4;
        Object concObj = node.config().get("maxConcurrency");
        if (concObj instanceof Number n) maxConcurrency = Math.max(1, Math.min(n.intValue(), 16));

        Object source = resolvePath(context, over);
        if (!(source instanceof List<?> items)) {
            // Allow empty/missing as no-op rather than failure — common for "iterate over results that may be empty".
            return StepResult.success(Map.of(node.id(), List.of()), null);
        }
        if (items.isEmpty()) {
            return StepResult.success(Map.of(node.id(), List.of()), null);
        }

        int currentDepth = 0;
        Object depthObj = runOptions == null ? null : runOptions.get("subchainDepth");
        if (depthObj instanceof Number n) currentDepth = n.intValue();
        if (currentDepth >= MAX_SUBCHAIN_DEPTH) {
            return StepResult.failed("iterator depth limit reached (" + MAX_SUBCHAIN_DEPTH + ") — possible recursion");
        }

        String chatSessionId = childChatSessionId(effectiveSessionId, parentRunId);
        String initiatedBy = inheritedInitiatedBy(parentRunId);
        boolean continueOnFail = Boolean.TRUE.equals(node.config().get("continueOnFail"));

        Map<String, Object> baseChildOptions = new LinkedHashMap<>();
        if (runOptions != null) baseChildOptions.putAll(runOptions);
        baseChildOptions.put("subchainDepth", currentDepth + 1);
        baseChildOptions.put("async", false);

        final String asKey = as;
        final String chainId = subChainId;
        final Integer version = subVersion;

        java.util.concurrent.Semaphore limiter = new java.util.concurrent.Semaphore(maxConcurrency);
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> itemInput = new LinkedHashMap<>(context);
            itemInput.put(asKey, item);
            CompletableFuture<Object> fut;
            if (parallel) {
                fut = CompletableFuture.supplyAsync(() -> {
                    try {
                        limiter.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Map.of("error", "interrupted");
                    }
                    try {
                        return runIteratorItem(chainId, version, initiatedBy, itemInput, baseChildOptions, sender, chatSessionId, continueOnFail);
                    } finally {
                        limiter.release();
                    }
                }, branchExecutor);
            } else {
                fut = CompletableFuture.completedFuture(
                        runIteratorItem(chainId, version, initiatedBy, itemInput, baseChildOptions, sender, chatSessionId, continueOnFail));
            }
            futures.add(fut);
        }

        List<Object> results = new ArrayList<>();
        for (CompletableFuture<Object> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                if (continueOnFail) {
                    results.add(Map.of("error", e.getMessage() == null ? "iterator item failed" : e.getMessage()));
                } else {
                    return StepResult.failed("iterator item failed: " + e.getMessage());
                }
            }
        }
        return StepResult.success(Map.of(node.id(), results), null);
    }

    private Object runIteratorItem(String subChainId, Integer subVersion, String initiatedBy,
                                   Map<String, Object> itemInput, Map<String, Object> baseChildOptions,
                                   SseEventSender sender, String chatSessionId, boolean continueOnFail) {
        try {
            ToolChainRun childRun = execute(subChainId, subVersion, "iterator", initiatedBy,
                    itemInput, baseChildOptions, sender, chatSessionId);
            return parseFlexibleBody(extractResultFromSnapshot(childRun.getOutputSnapshot()));
        } catch (Exception e) {
            if (continueOnFail) {
                return Map.of("error", e.getMessage() == null ? "iterator item failed" : e.getMessage());
            }
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveSubchainInput(NodeModel node, Map<String, Object> context) {
        Map<String, Object> mappings = node.configMap("inputMappings");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            String targetKey = entry.getKey();
            if (targetKey == null || targetKey.isBlank()) continue;
            Object source = entry.getValue();
            Object value = source instanceof String
                    ? resolvePath(context, String.valueOf(source))
                    : source;
            if (value != null) out.put(targetKey, value);
        }
        return out;
    }

    private String childChatSessionId(String effectiveSessionId, String parentRunId) {
        // For chat-driven parents effectiveSessionId IS the chat session id; pass it on so child
        // events flow to the same chat. For non-chat parents effectiveSessionId equals the parent
        // runId, which is not a real chat session — pass null so the FK isn't violated.
        if (effectiveSessionId == null) return null;
        if (parentRunId != null && effectiveSessionId.equals(parentRunId)) return null;
        return effectiveSessionId;
    }

    private String inheritedInitiatedBy(String parentRunId) {
        if (parentRunId == null) return null;
        return runRepository.findById(parentRunId).map(ToolChainRun::getInitiatedBy).orElse(null);
    }

    private String extractResultFromSnapshot(String outputSnapshot) {
        if (outputSnapshot == null || outputSnapshot.isBlank()) return null;
        Map<String, Object> outer = fromJsonMap(outputSnapshot);
        Object result = outer.get("result");
        if (result instanceof String s) return s;
        if (result != null) return toJson(result);
        return null;
    }

    private StepResult executeSynthesisNode(NodeModel node,
                                            Map<String, Object> context,
                                            String runId,
                                            SseEventSender sender,
                                            String effectiveSessionId) {
        // Prefer the version-level synthesisPrompt — that's where the architect skill writes the
        // actual synthesis instructions. The node-level config.prompt is rarely set and was
        // previously forced to the generic "Summarize the workflow output" default, which (a)
        // ignored the architect's prompt and (b) caused finalizeResponse to re-run synthesis with
        // the real prompt, producing duplicate LLM calls and skill loads.
        String toolChainId = runRepository.findById(runId).map(ToolChainRun::getToolChainId).orElse(null);
        String prompt = node.configString("prompt");
        if (prompt == null || prompt.isBlank()) {
            prompt = resolveVersionSynthesisPrompt(runId);
        }
        if (prompt == null || prompt.isBlank()) {
            prompt = "Summarize the workflow output clearly for end users.";
        }
        ModelRef modelRef = resolveModelRefForSynthesis(Map.of(), toolChainId);
        if (modelRef == null) {
            return StepResult.failed("Synthesis node requires a configured model");
        }
        String summary = callSynthesisLlm(prompt, context, runId, modelRef, sender, effectiveSessionId);
        return StepResult.success(Map.of(node.id(), Map.of("summary", summary)), null);
    }

    @SuppressWarnings("unchecked")
    private String extractGraphSynthesisOutput(ToolChainVersion version, Map<String, Object> context) {
        if (version == null || context == null || context.isEmpty()) return null;
        Map<String, Object> graph = fromJsonMap(version.getGraphJson());
        if (graph.isEmpty()) return null;
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodeList)) return null;
        for (Object n : nodeList) {
            if (!(n instanceof Map<?, ?> nodeMap)) continue;
            Object type = nodeMap.get("type");
            if (type == null || !"synthesis".equalsIgnoreCase(String.valueOf(type))) continue;
            Object id = nodeMap.get("id");
            if (id == null) continue;
            Object value = context.get(String.valueOf(id));
            if (!(value instanceof Map<?, ?> valueMap)) continue;
            Object summary = ((Map<String, Object>) valueMap).get("summary");
            if (summary instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private String resolveVersionSynthesisPrompt(String runId) {
        if (runId == null || runId.isBlank()) return null;
        try {
            var run = runRepository.findById(runId).orElse(null);
            if (run == null) return null;
            return toolChainService
                    .resolveVersion(run.getToolChainId(), run.getVersion())
                    .map(ToolChainVersion::getSynthesisPrompt)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean requiresApproval(NodeModel node) {
        String mode = node.approvalMode();
        if (mode == null) return false;
        return "required".equalsIgnoreCase(mode) || "required_if_sensitive".equalsIgnoreCase(mode);
    }

    private Map<String, Object> resolveNodeInput(NodeModel node, Map<String, Object> context) {
        String inputPath = node.configString("inputKey");
        Map<String, Object> payload;
        if (inputPath == null || inputPath.isBlank()) {
            payload = new LinkedHashMap<>(context);
        } else {
            Object value = resolvePath(context, inputPath);
            if (value instanceof Map<?, ?> m) {
                payload = new LinkedHashMap<>((Map<String, Object>) m);
            } else {
                String targetKey = inputPath.contains(".")
                        ? inputPath.substring(inputPath.lastIndexOf('.') + 1)
                        : inputPath;
                payload = new LinkedHashMap<>();
                if (value != null) payload.put(targetKey, value);
            }
        }
        Map<String, Object> argMappings = node.configMap("argMappings");
        for (Map.Entry<String, Object> entry : argMappings.entrySet()) {
            String targetArg = entry.getKey();
            if (targetArg == null || targetArg.isBlank()) continue;
            Object source = entry.getValue();
            Object value = source instanceof String
                    ? resolvePath(context, String.valueOf(source))
                    : source;
            if (value != null) {
                payload.put(targetArg, value);
            }
        }
        return payload;
    }

    private Object resolvePath(Map<String, Object> context, String keyPath) {
        if (context == null || keyPath == null || keyPath.isBlank()) return null;
        if (context.containsKey(keyPath)) return context.get(keyPath);
        String[] parts = keyPath.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) return null;
            Object next = null;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && String.valueOf(entry.getKey()).equalsIgnoreCase(part)) {
                    next = entry.getValue();
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private void completeRun(String runId, String status, Map<String, Object> output, String error, String effectiveSessionId) {
        runRepository.updateStatus(runId, status, toJson(output), error);
        saveRuntimeEvent(runId, "toolchain.run.completed", Map.of("status", status, "error", error == null ? "" : error), effectiveSessionId);
    }

    private void saveRuntimeEvent(String runId, String eventType, Map<String, Object> payload, String effectiveSessionId) {
        // runtime_events.session_id has an FK to chat_sessions. For non-chat runs,
        // effectiveSessionId falls back to runId (so SSE payloads have a value), but
        // that runId is NOT a chat session — persisting it would trip the FK and abort
        // the run after task.started. Null is permitted by the FK and is the right
        // value when there's no originating chat session.
        String dbSessionId = (effectiveSessionId == null || effectiveSessionId.equals(runId))
                ? null
                : effectiveSessionId;
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(dbSessionId)
                .turnId(runId)
                .eventType(eventType)
                .payload(toJson(payload))
                .build());
    }

    private String finalizeResponse(String toolChainId,
                                    ToolChainVersion version,
                                    Map<String, Object> context,
                                    String runId, Map<String, Object> options, SseEventSender sender,
                                    String effectiveSessionId) {
        String mode = version.getResponseMode() == null ? "hybrid" : version.getResponseMode();

        if ("raw_graph_output".equalsIgnoreCase(mode)) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("data", context);
            String json = toJson(output);
            if (sender != null) sender.sendTextDelta(json);
            return json;
        }

        // If the graph contains a synthesis node, that node already ran the version-level
        // synthesisPrompt (see executeSynthesisNode's fallback) and stored its output in
        // context[<nodeId>].summary. Reuse it rather than running the synthesis LLM again,
        // which would duplicate the work and re-load every skill.
        ModelRef modelRef = resolveModelRefForSynthesis(options, toolChainId);
        String synthesisText = extractGraphSynthesisOutput(version, context);
        boolean synthesisFromGraph = synthesisText != null;
        if (synthesisText == null) {
            String synthesisPrompt = version.getSynthesisPrompt();
            if (synthesisPrompt != null && !synthesisPrompt.isBlank() && modelRef != null) {
                synthesisText = callSynthesisLlm(synthesisPrompt, context, runId, modelRef, sender, effectiveSessionId);
            } else if (modelRef == null) {
                synthesisText = "Synthesis failed: no runtime/default model configured.";
            } else {
                synthesisText = "Chain completed successfully.";
            }
        }

        // Skip the JSON-shape LLM pass when the graph's synthesis node already produced
        // (and streamed) the answer — re-shaping content the user has already seen is
        // a wasted second LLM round-trip.
        String shaped = synthesisFromGraph ? null : shapeStructuredOutput(version, context, synthesisText, runId, modelRef, sender);
        if (shaped != null && !shaped.isBlank()) {
            return shaped;
        }
        if ("synthesized_text".equalsIgnoreCase(mode)) {
            return synthesisText;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("data", context);
        fallback.put("summary", synthesisText);
        return toJson(fallback);
    }

    @SuppressWarnings("unchecked")
    private ModelRef extractModelRef(Map<String, Object> options) {
        if (options == null) return null;
        Object raw = options.get("model");
        if (raw == null) raw = options.get("modelRef");
        if (!(raw instanceof Map<?, ?> map)) return null;
        Object pid = map.get("providerID");
        Object mid = map.get("modelID");
        if (pid == null || mid == null) return null;
        String providerId = String.valueOf(pid).trim();
        String modelId = String.valueOf(mid).trim();
        if (providerId.isBlank() || modelId.isBlank()) return null;
        return new ModelRef(providerId, modelId);
    }

    @SuppressWarnings("unchecked")
    private ModelRef resolveModelRefForSynthesis(Map<String, Object> options, String toolChainId) {
        ModelRef fromOptions = extractModelRef(options);
        if (fromOptions != null) return fromOptions;
        if (toolChainId == null || toolChainId.isBlank()) return null;
        try {
            var chain = toolChainService.getRequired(toolChainId);
            Map<String, Object> metadata = fromJsonMap(chain.getMetadataJson());
            Object runtimeModelRef = metadata.get("runtimeModelRef");
            if (!(runtimeModelRef instanceof Map<?, ?>)) {
                runtimeModelRef = metadata.get("defaultModelRef");
            }
            if (!(runtimeModelRef instanceof Map<?, ?> map)) return null;
            Object pid = map.get("providerID");
            Object mid = map.get("modelID");
            if (pid == null || mid == null) return null;
            String providerId = String.valueOf(pid).trim();
            String modelId = String.valueOf(mid).trim();
            if (providerId.isBlank() || modelId.isBlank()) return null;
            return new ModelRef(providerId, modelId);
        } catch (Exception e) {
            log.warn("[ToolChainRuntimeService] Unable to resolve default ToolChain model for {}: {}",
                    toolChainId, e.getMessage());
            return null;
        }
    }

    private String callSynthesisLlm(String synthesisPrompt, Map<String, Object> context,
                                    String runId, ModelRef modelRef, SseEventSender sender,
                                    String effectiveSessionId) {
        SseEventSender effectiveSender = sender != null ? sender : new SseEventSender(null, objectMapper);
        SkillExecutionGate gate = new SkillExecutionGate(false);
        // Same FK reasoning as saveRuntimeEvent: when there's no chat session backing
        // this run, effectiveSessionId == runId (a non-chat-session id), so persisting
        // skill tool.call/tool.done events under it would violate the FK. Pass null in
        // that case; SkillToolCallback's saveRuntimeEvent lets the column be null.
        String skillSessionId = effectiveSessionId == null || effectiveSessionId.equals(runId)
                ? null
                : effectiveSessionId;
        ToolCallback skillTool = new SkillToolCallback(
                skillRegistryService,
                runtimeTuningProperties,
                effectiveSender,
                skillSessionId,
                runId,
                objectMapper,
                runtimeEventRepository,
                gate
        );

        StringBuilder skillsCatalog = new StringBuilder();
        var enabledSkills = skillRegistryService.getEnabledSkills();
        if (enabledSkills != null && !enabledSkills.isEmpty()) {
            skillsCatalog.append("\n\nAvailable skills (load via the `skill` tool with `name=<name>`):\n");
            for (var snapshot : enabledSkills) {
                if (snapshot == null || snapshot.skill() == null) continue;
                String name = snapshot.skill().getName();
                String desc = snapshot.skill().getDescription();
                skillsCatalog.append("- ").append(name);
                if (desc != null && !desc.isBlank()) {
                    skillsCatalog.append(": ").append(desc.trim());
                }
                skillsCatalog.append("\n");
            }
        }
        // Behavior rules prepended to the synthesis prompt so the model produces ONLY the final
        // report and never narrates its tool usage. The architect skill sometimes produces a prompt
        // that's too permissive about reasoning out loud; we enforce a strict no-narration policy
        // at runtime regardless of what the architect wrote.
        String behaviorRules = """
                # Output rules — STRICT
                - Output ONLY the final report. Begin with the first heading or sentence of the report.
                - NEVER narrate your tool usage, planning, or reasoning. Tool calls happen silently.
                - Forbidden openers (and any paraphrase of them):
                  - "I'll load...", "Let me load...", "First, I'll...", "I will now..."
                  - "All skills have been loaded.", "Now I'll apply...", "I have loaded..."
                  - "I'll analyze...", "Let me check...", "I need to..."
                - Do NOT acknowledge the user, the request, or the data fetch.
                - The first character of your response must be the start of the report itself.

                """;
        String fullSystemPrompt = behaviorRules + synthesisPrompt + skillsCatalog;
        String userMessage = toJson(context);

        // Reset the visible buffer whenever the model emits a tool-call response. Anything streamed
        // BEFORE a tool call is interleaved reasoning we want to discard; only text streamed AFTER
        // the last tool call is the actual answer.
        StringBuilder result = new StringBuilder();
        try {
            ChatClient client = modelProviderRouter.resolve(modelRef, true).client();
            client.prompt()
                    .system(fullSystemPrompt)
                    .user(userMessage)
                    .toolCallbacks(List.of(skillTool))
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        if (response == null || response.getResult() == null) return;
                        var output = response.getResult().getOutput();
                        if (output == null) return;
                        if (output.hasToolCalls()) {
                            // The model is about to (or just did) call a tool. Discard any narration
                            // streamed up to this point — the real answer comes after the last tool.
                            result.setLength(0);
                            return;
                        }
                        String delta = output.getText();
                        if (delta == null || delta.isEmpty()) return;
                        result.append(delta);
                        if (sender != null) sender.sendTextDelta(delta);
                    })
                    .blockLast();
        } catch (Exception e) {
            log.warn("[ToolChainRuntimeService] Synthesis LLM call failed: {}", e.getMessage(), e);
            String fallback = "Synthesis failed: " + e.getMessage();
            if (sender != null) sender.sendTextDelta(fallback);
            return fallback;
        }
        return stripNarrationPreamble(result.toString());
    }

    /**
     * Defensive trim — even with the no-narration system rule, a model occasionally still emits a
     * one-line preamble before the actual report. If the response starts with a known narration
     * pattern AND has a clear report start (markdown heading, separator, or blank-line break),
     * drop everything before that boundary.
     */
    private String stripNarrationPreamble(String raw) {
        if (raw == null) return "";
        String text = raw.stripLeading();
        if (text.isEmpty()) return text;
        String[] narrationStarts = {
                "i'll", "i will", "let me", "first,", "first ", "now i", "i have", "i've",
                "i need to", "i'm going to", "i am going to", "i'll now", "all skills",
                "all five", "the skills are", "the skill is loaded", "skills loaded"
        };
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean looksLikeNarration = false;
        for (String prefix : narrationStarts) {
            if (lower.startsWith(prefix)) {
                looksLikeNarration = true;
                break;
            }
        }
        if (!looksLikeNarration) return text;
        // Find the first markdown heading or horizontal rule — that's where the actual report starts.
        int headingIdx = indexOfReportStart(text);
        if (headingIdx > 0) {
            return text.substring(headingIdx).stripLeading();
        }
        // Couldn't locate a clean boundary; drop just the first paragraph (up to the first blank line).
        int blankBreak = text.indexOf("\n\n");
        if (blankBreak > 0 && blankBreak < text.length() - 2) {
            return text.substring(blankBreak + 2).stripLeading();
        }
        return text;
    }

    private int indexOfReportStart(String text) {
        int candidate = -1;
        int hashIdx = text.indexOf("\n#");
        if (hashIdx >= 0) candidate = hashIdx + 1;
        int hrIdx = text.indexOf("\n---");
        if (hrIdx >= 0 && (candidate < 0 || hrIdx + 1 < candidate)) candidate = hrIdx + 1;
        return candidate;
    }

    private String callShapingLlm(String systemPrompt, Map<String, Object> payload, ModelRef modelRef) {
        try {
            ChatClient client = modelProviderRouter.resolve(modelRef, true).client();
            String response = client.prompt()
                    .system(systemPrompt)
                    .user(toJson(payload))
                    .call()
                    .content();
            return response == null ? "" : response;
        } catch (Exception e) {
            log.warn("[ToolChainRuntimeService] Shaping LLM call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private String shapeStructuredOutput(ToolChainVersion version,
                                         Map<String, Object> context,
                                         String synthesisText,
                                         String runId,
                                         ModelRef modelRef,
                                         SseEventSender sender) {
        Map<String, Object> schema = fromJsonMap(version.getOutputSchema());
        if (schema.isEmpty() || modelRef == null) return null;
        String contractPrompt = """
                Return ONLY valid JSON that matches this output schema exactly.
                Include summary text and transformed data from the workflow context.
                If required fields are missing, infer best-effort values but keep schema validity.
                Schema:
                """ + toJson(schema);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", context);
        payload.put("summary", synthesisText);
        // Use a tool-less LLM call here — JSON shaping does not need the `skill` tool, and
        // exposing it caused the model to load every catalog skill on each shape pass.
        String llmJson = callShapingLlm(contractPrompt, payload, modelRef);
        if (llmJson == null || llmJson.isBlank()) return null;
        Object parsed = parseFlexibleBody(llmJson);
        if (!(parsed instanceof Map<?, ?> parsedMap)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = new LinkedHashMap<>((Map<String, Object>) parsedMap);
        List<String> validationErrors = validateOutputAgainstSchema(candidate, schema);
        if (!validationErrors.isEmpty()) {
            String failure = "Schema validation failed: " + String.join("; ", validationErrors);
            if (sender != null) sender.sendTextDelta(failure);
            return toJson(Map.of(
                    "summary", synthesisText,
                    "data", context,
                    "validationError", failure
            ));
        }
        return toJson(candidate);
    }

    private List<String> validateOutputAgainstSchema(Map<String, Object> payload, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            for (Object item : requiredList) {
                String key = String.valueOf(item);
                if (!payload.containsKey(key) || payload.get(key) == null) {
                    errors.add("missing required field: " + key);
                }
            }
        }
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> propsMap)) return errors;
        for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = payload.get(key);
            if (value == null) continue;
            if (!(entry.getValue() instanceof Map<?, ?> propMap)) continue;
            Object typeObj = propMap.get("type");
            String type = typeObj == null ? "" : String.valueOf(typeObj);
            if (type.isBlank()) continue;
            boolean valid = switch (type) {
                case "string" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
                case "boolean" -> value instanceof Boolean;
                case "array" -> value instanceof List<?>;
                case "object" -> value instanceof Map<?, ?>;
                default -> true;
            };
            if (!valid) errors.add("field '" + key + "' must be " + type);
        }
        return errors;
    }

    private void validateInputAgainstSchema(ToolChainVersion version, Map<String, Object> input) {
        String rawSchema = version.getInputSchema();
        if (rawSchema == null || rawSchema.isBlank()) return;
        Map<String, Object> schema = fromJsonMap(rawSchema);
        if (schema.isEmpty()) return;

        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            List<String> missing = new ArrayList<>();
            for (Object item : requiredList) {
                String key = String.valueOf(item);
                if (!input.containsKey(key) || input.get(key) == null || String.valueOf(input.get(key)).isBlank()) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Missing required input fields: " + String.join(", ", missing));
            }
        }

        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> propsMap)) return;
        for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!input.containsKey(key) || input.get(key) == null) continue;
            Object schemaProp = entry.getValue();
            if (!(schemaProp instanceof Map<?, ?> propMap)) continue;
            Object typeObj = propMap.get("type");
            String type = typeObj == null ? "" : String.valueOf(typeObj);
            if (type.isBlank()) continue;
            Object value = input.get(key);
            boolean valid = switch (type) {
                case "string" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
                case "boolean" -> value instanceof Boolean;
                case "array" -> value instanceof List<?>;
                case "object" -> value instanceof Map<?, ?>;
                default -> true;
            };
            if (!valid) {
                throw new IllegalArgumentException("Input field '" + key + "' must be of type " + type);
            }
        }
    }

    private String normalizeTriggerSource(String triggerSource) {
        if (triggerSource == null || triggerSource.isBlank()) return "api";
        String normalized = triggerSource.trim().toLowerCase(Locale.ROOT);
        if ("ui".equals(normalized)) return "manual";
        return ALLOWED_TRIGGER_SOURCES.contains(normalized) ? normalized : "api";
    }

    private GraphModel parseGraph(String graphJson) {
        Map<String, Object> parsed = fromJsonMap(graphJson);
        List<NodeModel> nodes = new ArrayList<>();
        List<EdgeModel> edges = new ArrayList<>();
        Object nodesObj = parsed.get("nodes");
        if (nodesObj instanceof List<?> nodeList) {
            for (Object row : nodeList) {
                if (row instanceof Map<?, ?> map) nodes.add(NodeModel.from((Map<String, Object>) map));
            }
        }
        Object edgesObj = parsed.get("edges");
        if (edgesObj instanceof List<?> edgeList) {
            for (Object row : edgeList) {
                if (row instanceof Map<?, ?> map) edges.add(EdgeModel.from((Map<String, Object>) map));
            }
        }
        return new GraphModel(nodes, edges);
    }

    private Map<String, Object> fromJsonMap(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Object parseFlexibleBody(String body) {
        if (body == null) return null;
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception ignored) {
            return body;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ApprovalDecision(boolean approved, String comment) {}

    private record StepResult(boolean success, boolean rejected, String error, Map<String, Object> outputs, List<String> nextNodes) {
        static StepResult success(Map<String, Object> outputs, List<String> nextNodes) {
            return new StepResult(true, false, null, outputs, nextNodes);
        }

        static StepResult failed(String error) {
            return new StepResult(false, false, error, Map.of(), List.of());
        }

        static StepResult rejected(String error) {
            return new StepResult(false, true, error, Map.of(), List.of());
        }
    }

    private record GraphModel(List<NodeModel> nodes, List<EdgeModel> edges) {
        Map<String, NodeModel> nodesById() {
            Map<String, NodeModel> out = new LinkedHashMap<>();
            for (NodeModel node : nodes) out.put(node.id(), node);
            return out;
        }

        Map<String, List<String>> outgoing() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (NodeModel node : nodes) out.put(node.id(), new ArrayList<>());
            for (EdgeModel edge : edges) {
                out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            }
            return out;
        }

        Map<String, Integer> indegree() {
            Map<String, Integer> indegree = new LinkedHashMap<>();
            for (NodeModel node : nodes) indegree.put(node.id(), 0);
            for (EdgeModel edge : edges) {
                indegree.put(edge.to(), indegree.getOrDefault(edge.to(), 0) + 1);
            }
            return indegree;
        }
    }

    private record NodeModel(String id,
                             String type,
                             String label,
                             String toolRef,
                             Map<String, Object> config,
                             String approvalMode,
                             String approvalGroup,
                             String approvalPrompt,
                             long approvalTimeoutMs) {
        static NodeModel from(Map<String, Object> row) {
            Map<String, Object> config = row.get("config") instanceof Map<?, ?> c ? new LinkedHashMap<>((Map<String, Object>) c) : new LinkedHashMap<>();
            String approvalMode = String.valueOf(config.getOrDefault("approvalMode", row.getOrDefault("approvalMode", "none")));
            String approvalGroup = stringValue(config.getOrDefault("approvalGroup", row.get("approvalGroup")));
            String approvalPrompt = stringValue(config.getOrDefault("approvalPrompt", row.get("approvalPrompt")));
            long timeoutMs = 300_000L;
            Object timeoutObj = config.getOrDefault("approvalTimeoutMs", row.get("approvalTimeoutMs"));
            if (timeoutObj instanceof Number n) timeoutMs = Math.max(5_000L, n.longValue());
            String toolName = stringValue(config.getOrDefault("toolName", row.get("toolRef")));
            return new NodeModel(
                    String.valueOf(row.get("id")),
                    String.valueOf(row.getOrDefault("type", "tool")),
                    String.valueOf(row.getOrDefault("label", row.get("id"))),
                    toolName,
                    config,
                    approvalMode,
                    approvalGroup,
                    approvalPrompt,
                    timeoutMs
            );
        }

        private static String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        String configString(String key) {
            Object value = config.get(key);
            return value == null ? null : String.valueOf(value);
        }

        Map<String, Object> configMap(String key) {
            Object value = config.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
            return Map.of();
        }
    }

    private record EdgeModel(String from, String to) {
        static EdgeModel from(Map<String, Object> row) {
            return new EdgeModel(String.valueOf(row.get("from")), String.valueOf(row.get("to")));
        }
    }
}
