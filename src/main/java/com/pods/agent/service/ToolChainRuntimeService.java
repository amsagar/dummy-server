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
import com.pods.agent.service.codeexec.CodeExecutionResult;
import com.pods.agent.service.codeexec.CodeExecutionService;
import com.pods.agent.service.expression.BooleanExpressionEvaluator;
import com.pods.agent.service.expression.PathResolver;
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
    private final ArgMappingResolver argMappingResolver;
    private final LlmArgResolver llmArgResolver;
    private final BooleanExpressionEvaluator booleanExpressionEvaluator;
    private final CodeExecutionService codeExecutionService;
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
                                   ObjectMapper objectMapper,
                                   ArgMappingResolver argMappingResolver,
                                   LlmArgResolver llmArgResolver,
                                   BooleanExpressionEvaluator booleanExpressionEvaluator,
                                   CodeExecutionService codeExecutionService) {
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
        this.argMappingResolver = argMappingResolver;
        this.llmArgResolver = llmArgResolver;
        this.booleanExpressionEvaluator = booleanExpressionEvaluator;
        this.codeExecutionService = codeExecutionService;
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
        Map<String, List<EdgeModel>> outgoing = graph.outgoingEdges();
        Map<String, Integer> indegree = graph.indegree();
        // Resolve runtime model once per run so llm_assisted argMappings can fall back without
        // rediscovering the model on every step. Stored on a mutable runOptions copy under an
        // internal underscore key that never reaches tools.
        // Build a mutable per-run options map without reassigning the parameter — the lambda
        // below captures the variable used in this method, and Java requires effective finality.
        Map<String, Object> runOptions = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
        ModelRef runtimeModelRef = resolveModelRefForSynthesis(runOptions, run.getToolChainId());
        if (runtimeModelRef != null) runOptions.put("_runtime_modelRef", runtimeModelRef);
        runOptions.put("_runtime_versionId", version.getId());
        Map<String, Object> context = new LinkedHashMap<>(input);
        context.put("chainInput", new LinkedHashMap<>(input));
        Map<String, Object> vars = initializeVariables(graph.variables());
        vars.putAll(initializeVariables(fromJsonListMap(version.getVariablesJson())));
        context.put("vars", vars);
        Queue<String> ready = new ArrayDeque<>();
        indegree.forEach((k, v) -> {
            if (v == 0) ready.offer(k);
        });

        String finalStatus = "success";
        while (!ready.isEmpty()) {
            List<String> batch = new ArrayList<>();
            while (!ready.isEmpty()) batch.add(ready.poll());
            List<CompletableFuture<StepResult>> futures = batch.stream()
                    .map(nodeId -> CompletableFuture.supplyAsync(() -> executeNode(runId, nodes.get(nodeId), context, sender, effectiveSessionId, runOptions), branchExecutor))
                    .toList();
            for (int i = 0; i < batch.size(); i++) {
                String nodeId = batch.get(i);
                StepResult result = futures.get(i).join();
                if (!result.success()) {
                    if (!result.rejected()) {
                        List<String> errorNext = selectEdgeTargets(outgoing.getOrDefault(nodeId, List.of()), "error", context, result.error());
                        if (!errorNext.isEmpty()) {
                            Map<String, Object> recovered = new LinkedHashMap<>();
                            recovered.put("error", result.error());
                            recovered.put("recovered", true);
                            context.put(nodeId, recovered);
                            for (String to : errorNext) {
                                indegree.put(to, indegree.getOrDefault(to, 1) - 1);
                                if (indegree.get(to) <= 0) ready.offer(to);
                            }
                            continue;
                        }
                    }
                    finalStatus = result.rejected() ? "rejected" : "failed";
                    completeRun(runId, finalStatus, context, result.error(), effectiveSessionId);
                    return runRepository.findById(runId).orElse(run);
                }
                if (result.outputs() != null) context.putAll(result.outputs());

                List<String> next = selectEdgeTargets(outgoing.getOrDefault(nodeId, List.of()), "success", context, null);
                if (result.nextNodes() != null && !result.nextNodes().isEmpty()) {
                    next = result.nextNodes();
                }
                for (String to : next) {
                    indegree.put(to, indegree.getOrDefault(to, 1) - 1);
                    if (indegree.get(to) <= 0) ready.offer(to);
                }
            }
        }

        String output = finalizeResponse(run.getToolChainId(), version, context, runId, runOptions, sender, effectiveSessionId);
        completeRun(runId, "success", Map.of("result", output, "context", context), null, effectiveSessionId);
        return runRepository.findById(runId).orElse(run);
    }

    private List<String> selectEdgeTargets(List<EdgeModel> edges,
                                           String kind,
                                           Map<String, Object> context,
                                           String errorMessage) {
        if (edges == null || edges.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (EdgeModel edge : edges) {
            String edgeKind = edge.kind() == null || edge.kind().isBlank() ? "success" : edge.kind().toLowerCase(Locale.ROOT);
            if (!edgeKind.equals(kind)) continue;
            String condition = edge.condition();
            if (condition != null && !condition.isBlank()) {
                Map<String, Object> evalContext = context;
                if ("error".equals(kind)) {
                    evalContext = new LinkedHashMap<>(context);
                    evalContext.put("error", Map.of("message", errorMessage == null ? "" : errorMessage));
                }
                if (!booleanExpressionEvaluator.eval(condition, evalContext)) continue;
            }
            out.add(edge.to());
            if ("error".equals(kind)) break;
        }
        return out;
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
                case "assign" -> executeAssign(node, context);
                case "parallel" -> StepResult.success(Map.of(node.id(), Map.of("parallel", true)), null);
                case "code_execute" -> executeCodeNode(node, context);
                case "tool", "mcp_tool" -> executeToolNode(node, context, runOptions);
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

    private StepResult executeToolNode(NodeModel node, Map<String, Object> context, Map<String, Object> runOptions) {
        String toolName = node.toolRef();
        if (toolName == null || toolName.isBlank()) {
            return StepResult.failed("Tool node is missing tool reference");
        }
        AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
        if (tool == null) {
            return StepResult.failed("Tool is not enabled: " + toolName);
        }
        Map<String, Object> resolvedInput = resolveNodeInput(node, context);
        // JSONata predicates return arrays. When the LLM-authored mapping forgets the [0]
        // unwrap and the tool's schema expects a scalar, coerce single-element lists to their
        // sole element. Belt-and-suspenders against authoring bugs that slip past validation.
        coerceScalarArgs(resolvedInput, tool);
        // Fill in any args that are marked policy=llm_assisted but came back null from the
        // deterministic JSONata path. Best-effort; missing values stay missing if the LLM
        // is unavailable or the model can't determine a value.
        Object modelRefObj = runOptions == null ? null : runOptions.get("_runtime_modelRef");
        ModelRef modelRef = modelRefObj instanceof ModelRef m ? m : null;
        Object versionIdObj = runOptions == null ? null : runOptions.get("_runtime_versionId");
        String versionId = versionIdObj instanceof String s ? s : null;
        if (modelRef != null) {
            applyLlmAssistedArgs(node, context, resolvedInput, toolName, modelRef, versionId);
        }
        String payload = toJson(resolvedInput);
        ToolExecutionService.ExecutionResult result = toolExecutionService.execute(tool, payload);
        if (!result.success()) {
            return StepResult.failed(result.error());
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("input", resolvedInput);
        step.put("output", parseFlexibleBody(result.body()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(node.id(), step);
        return StepResult.success(out, null);
    }

    @SuppressWarnings("unchecked")
    private StepResult executeCodeNode(NodeModel node, Map<String, Object> context) {
        String language = node.configString("language");
        String code = node.configString("code");
        if (code == null || code.isBlank()) {
            return StepResult.failed("Code node is missing executable code.");
        }
        Map<String, Object> resolvedInput = resolveCodeNodeInput(node, context);
        Long timeout = asLong(node.config().get("timeoutMs"));
        Integer memory = asInt(node.config().get("memoryLimitMb"));
        CodeExecutionResult result = codeExecutionService.execute(language, code, resolvedInput, timeout, memory);
        if (!result.success()) {
            return StepResult.failed(formatCodeExecutionError(result));
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("input", resolvedInput);
        step.put("output", result.output());
        if (!result.stdout().isBlank()) step.put("stdout", result.stdout());
        if (!result.stderr().isBlank()) step.put("stderr", result.stderr());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(node.id(), step);
        return StepResult.success(out, null);
    }

    private Map<String, Object> resolveCodeNodeInput(NodeModel node, Map<String, Object> context) {
        Object inputsObj = node.config().get("inputs");
        if (!(inputsObj instanceof List<?> rows) || rows.isEmpty()) {
            return resolveNodeInput(node, context);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> raw)) continue;
            String name = raw.get("name") == null ? "" : String.valueOf(raw.get("name")).trim();
            if (name.isBlank() && raw.get("as") != null) {
                name = String.valueOf(raw.get("as")).trim();
            }
            if (name.isBlank()) continue;
            Object source = raw.containsKey("expression")
                    ? raw.get("expression")
                    : (raw.containsKey("from")
                    ? raw.get("from")
                    : (raw.containsKey("source") ? raw.get("source") : raw.get("value")));
            Object value = argMappingResolver.resolveOne(source, context, key -> resolvePath(context, key));
            // Backward compatibility: historical configs used `from: "<nodeId>"` and expected
            // that to mean prior step output, not the full {input,output} envelope.
            if ((raw.containsKey("from") || raw.containsKey("source")) && value instanceof Map<?, ?> map && isStepRecord(map)) {
                value = lookupKey(map, "output");
            }
            out.put(name, value);
        }
        return out;
    }

    private String formatCodeExecutionError(CodeExecutionResult result) {
        String base = result.error() == null || result.error().isBlank()
                ? "Code execution failed."
                : result.error();
        String stderr = result.stderr();
        String stdout = result.stdout();
        if (stderr != null && !stderr.isBlank()) {
            base += " stderr: " + truncateForError(stderr);
        } else if (stdout != null && !stdout.isBlank()) {
            base += " stdout: " + truncateForError(stdout);
        }
        return base;
    }

    private String truncateForError(String text) {
        if (text == null) return "";
        String normalized = text.trim().replace('\n', ' ').replace('\r', ' ');
        int max = 700;
        if (normalized.length() <= max) return normalized;
        return normalized.substring(0, max) + "...";
    }

    private Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer asInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * For each arg in resolvedInput where the tool's schema declares a scalar type
     * (string/integer/number/boolean), if the value is a List of length 1, replace it with
     * its sole element. Catches the common JSONata-predicate-returns-array bug where the
     * LLM-authored expression forgot [0]. Multi-element lists are left alone because that
     * almost certainly indicates a deeper authoring bug — surfacing them via the tool's
     * own schema validation is more useful than silently picking the first element.
     */
    @SuppressWarnings("unchecked")
    private void coerceScalarArgs(Map<String, Object> resolvedInput, AgentTool tool) {
        if (resolvedInput == null || resolvedInput.isEmpty() || tool == null) return;
        String schemaJson = tool.getRequestSchema();
        if (schemaJson == null || schemaJson.isBlank()) return;
        Map<String, Object> schemaProps;
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, Map.class);
            Object props = schema.get("properties");
            if (!(props instanceof Map<?, ?>)) return;
            schemaProps = (Map<String, Object>) props;
        } catch (Exception e) {
            return;
        }
        for (Map.Entry<String, Object> entry : new java.util.ArrayList<>(resolvedInput.entrySet())) {
            Object value = entry.getValue();
            if (!(value instanceof List<?> list) || list.size() != 1) continue;
            Object propDef = schemaProps.get(entry.getKey());
            if (!(propDef instanceof Map<?, ?> propMap)) continue;
            Object type = propMap.get("type");
            if (!(type instanceof String t)) continue;
            switch (t.toLowerCase(Locale.ROOT)) {
                case "string", "integer", "number", "boolean" -> resolvedInput.put(entry.getKey(), list.get(0));
                default -> { /* leave arrays/objects alone */ }
            }
        }
    }

    private void applyLlmAssistedArgs(NodeModel node,
                                      Map<String, Object> context,
                                      Map<String, Object> resolvedInput,
                                      String toolName,
                                      ModelRef modelRef,
                                      String versionId) {
        Map<String, Object> argMappings = node.configMap("argMappings");
        if (argMappings == null || argMappings.isEmpty()) return;
        ArgMappingResolver.ResolutionResult res = argMappingResolver.resolveAll(
                argMappings, context, key -> resolvePath(context, key));
        for (ArgMappingResolver.DeferredArg deferred : res.deferred()) {
            String argName = deferred.argName();
            if (resolvedInput.containsKey(argName)) continue;
            Object hint = deferred.mapping() == null ? null : deferred.mapping().expr();
            String hintExpr = hint instanceof String s ? s : null;
            llmArgResolver.resolve(toolName, argName, context, modelRef, hintExpr).ifPresent(resolved -> {
                if (resolved.value() != null) {
                    resolvedInput.put(argName, resolved.value());
                }
                // Self-heal: if the LLM also proposed a JSONata expression and that expression,
                // evaluated against the same context, produces the same value, persist it back
                // to the version's graphJson so future runs are fully deterministic.
                if (versionId != null && resolved.learnedExpr() != null && resolved.value() != null) {
                    Object evaluated = argMappingResolver.evaluateJsonata(resolved.learnedExpr(), context);
                    if (deepEquals(evaluated, resolved.value())) {
                        try {
                            boolean persisted = toolChainService.persistLearnedExpression(
                                    versionId, node.id(), argName, resolved.learnedExpr());
                            if (persisted) {
                                log.info("[ToolChainRuntimeService] Self-healed mapping {}#{} arg='{}' expr='{}'",
                                        versionId, node.id(), argName, resolved.learnedExpr());
                            }
                        } catch (Exception e) {
                            log.warn("[ToolChainRuntimeService] Failed to persist learned expression for {}#{} arg='{}': {}",
                                    versionId, node.id(), argName, e.getMessage());
                        }
                    }
                }
            });
        }
    }

    private boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        try {
            // Both go through Jackson 3 round-trip so number/string coercions converge
            // (e.g. "5038081" vs 5038081 from JSONata become equal once normalized).
            return objectMapper.writeValueAsString(a).equals(objectMapper.writeValueAsString(b));
        } catch (Exception e) {
            return a.equals(b);
        }
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
            Object fromContext = findRequiredInputInContext(context, key);
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

    private Object findRequiredInputInContext(Map<String, Object> context, String key) {
        if (context == null || context.isEmpty() || key == null || key.isBlank()) return null;
        Object fromPath = resolvePath(context, key);
        if (!isBlankValue(fromPath)) return fromPath;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && !isBlankValue(entry.getValue())) {
                return entry.getValue();
            }
            Object nested = nestedValueForRequiredKey(entry.getValue(), key);
            if (!isBlankValue(nested)) return nested;
        }
        return null;
    }

    private Object nestedValueForRequiredKey(Object container, String key) {
        if (!(container instanceof Map<?, ?> raw)) return null;
        Object direct = lookupKey(raw, key);
        if (!isBlankValue(direct)) return direct;
        Object output = lookupKey(raw, "output");
        if (output instanceof Map<?, ?> outputMap) {
            Object fromOutput = lookupKey(outputMap, key);
            if (!isBlankValue(fromOutput)) return fromOutput;
        }
        Object result = lookupKey(raw, "result");
        if (result instanceof Map<?, ?> resultMap) {
            Object fromResult = lookupKey(resultMap, key);
            if (!isBlankValue(fromResult)) return fromResult;
        }
        return null;
    }

    private String resolveToolChainReference(String chainRef) {
        if (chainRef == null || chainRef.isBlank()) return chainRef;
        String trimmed = chainRef.trim();
        try {
            toolChainService.getRequired(trimmed);
            return trimmed;
        } catch (Exception ignored) {
            // Fall back to name/slug resolution.
        }
        List<com.pods.agent.domain.ToolChain> chains = toolChainService.listAll();
        for (com.pods.agent.domain.ToolChain chain : chains) {
            String name = chain.getName();
            if (name != null && name.equalsIgnoreCase(trimmed)) {
                return chain.getId();
            }
        }
        String wantedSlug = slugifyChainRef(trimmed);
        if (!wantedSlug.isBlank()) {
            for (com.pods.agent.domain.ToolChain chain : chains) {
                String name = chain.getName();
                if (name == null || name.isBlank()) continue;
                if (slugifyChainRef(name).equals(wantedSlug)) {
                    return chain.getId();
                }
            }
        }
        return trimmed;
    }

    private String slugifyChainRef(String value) {
        if (value == null || value.isBlank()) return "";
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    private StepResult executeDecision(NodeModel node, Map<String, Object> context) {
        String expression = node.configString("expression");
        if (expression != null && !expression.isBlank()) {
            boolean matched = booleanExpressionEvaluator.eval(expression, context);
            String trueBranch = node.configString("trueBranch");
            String falseBranch = node.configString("falseBranch");
            List<String> next = matched ? (trueBranch == null ? List.of() : List.of(trueBranch))
                    : (falseBranch == null ? List.of() : List.of(falseBranch));
            Map<String, Object> out = Map.of(node.id(), matched ? "true" : "false");
            return StepResult.success(out, next);
        }
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
                Object whenExpression = caseMap.get("whenExpression");
                if (whenExpression != null && !String.valueOf(whenExpression).isBlank()) {
                    if (booleanExpressionEvaluator.eval(String.valueOf(whenExpression), context)) {
                        matchedCase = "__expression__";
                        Object to = caseMap.get("to");
                        if (to != null) target = String.valueOf(to);
                        break;
                    }
                    continue;
                }
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
            if (defaultBranch == null || defaultBranch.isBlank()) defaultBranch = node.configString("defaultBranch");
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

    private StepResult executeAssign(NodeModel node, Map<String, Object> context) {
        Object varsObj = context.get("vars");
        Map<String, Object> vars = varsObj instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Object assignmentsObj = node.config().get("assignments");
        if (!(assignmentsObj instanceof List<?> rows) || rows.isEmpty()) {
            return StepResult.failed("assign node missing assignments");
        }
        Map<String, Object> outAssignments = new LinkedHashMap<>();
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) continue;
            String var = row.get("var") == null ? null : String.valueOf(row.get("var"));
            if (var == null || var.isBlank()) continue;
            Object expr = row.get("expression");
            Object value = argMappingResolver.resolveOne(expr, context, key -> resolvePath(context, key));
            vars.put(var, value);
            outAssignments.put(var, value);
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("vars", vars);
        outputs.put(node.id(), outAssignments);
        return StepResult.success(outputs, null);
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
        String subChainRef = node.configString("chainId");
        if (subChainRef == null || subChainRef.isBlank()) {
            return StepResult.failed("subchain node missing 'chainId'");
        }
        String subChainId = resolveToolChainReference(subChainRef);
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
        String as = node.configString("as");
        if (as == null || as.isBlank()) as = "item";
        String loopMode = String.valueOf(node.config().getOrDefault("loopMode", "foreach")).toLowerCase(Locale.ROOT);
        String exitCondition = node.configString("exitCondition");
        int maxIterations = 1000;
        Object maxObj = node.config().get("maxIterations");
        if (maxObj instanceof Number n) maxIterations = Math.max(1, Math.min(n.intValue(), 10_000));
        if (!"foreach".equals(loopMode) && (exitCondition == null || exitCondition.isBlank())) {
            return StepResult.failed("iterator loopMode requires exitCondition");
        }
        boolean collectOutput = Boolean.TRUE.equals(node.config().get("collectOutput"));

        // Inline-tool mode: a single tool called once per item from a JSONata-resolved list,
        // with per-item argMappings that may reference $item.X. This is what the suggestion
        // service emits when it sees consecutive same-tool calls in a recorded turn.
        String inlineToolName = node.configString("toolName");
        if (inlineToolName != null && !inlineToolName.isBlank()) {
            return executeInlineToolIterator(node, context, runOptions, as, inlineToolName, loopMode, exitCondition, maxIterations, collectOutput);
        }

        // Legacy sub-chain mode: each item runs the same chain identified by subChainId.
        String over = node.configString("over");
        if (over == null || over.isBlank()) {
            return StepResult.failed("iterator node missing 'over' (or 'toolName' for inline mode)");
        }
        String subChainRef = node.configString("subChainId");
        if (subChainRef == null || subChainRef.isBlank()) {
            return StepResult.failed("iterator node missing 'subChainId'");
        }
        String subChainId = resolveToolChainReference(subChainRef);
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
        int i = 0;
        for (CompletableFuture<Object> f : futures) {
            if ("while".equals(loopMode)) {
                Map<String, Object> loopCtx = buildLoopContext(context, results, i, null);
                if (!booleanExpressionEvaluator.eval(exitCondition, loopCtx)) break;
            }
            try {
                Object itemResult = f.join();
                results.add(itemResult);
                if ("until".equals(loopMode)) {
                    Map<String, Object> loopCtx = buildLoopContext(context, results, i, itemResult);
                    if (booleanExpressionEvaluator.eval(exitCondition, loopCtx)) break;
                }
            } catch (Exception e) {
                if (continueOnFail) {
                    results.add(Map.of("error", e.getMessage() == null ? "iterator item failed" : e.getMessage()));
                } else {
                    return StepResult.failed("iterator item failed: " + e.getMessage());
                }
            }
            i++;
            if (i >= maxIterations) return StepResult.failed("iterator exceeded maxIterations=" + maxIterations);
        }
        if (collectOutput) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("results", results);
            envelope.put("output", results);
            return StepResult.success(Map.of(node.id(), envelope), null);
        }
        return StepResult.success(Map.of(node.id(), results), null);
    }

    /**
     * Iterator that calls a single tool once per item, with per-item argMappings evaluated
     * against the running context plus $item. The list of items is resolved from a special
     * "items" entry inside argMappings — its value is a JSONata expression yielding a list.
     * Per-item argMappings reference $item.X.
     *
     * Output shape: {nodeId: {input: {items: [...]}, output: [<each tool's output>...]}}
     * — same {input, output} envelope as a single tool node so downstream JSONata works.
     */
    @SuppressWarnings("unchecked")
    private StepResult executeInlineToolIterator(NodeModel node,
                                                 Map<String, Object> context,
                                                 Map<String, Object> runOptions,
                                                 String itemKey,
                                                 String toolName,
                                                 String loopMode,
                                                 String exitCondition,
                                                 int maxIterations,
                                                 boolean collectOutput) {
        AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
        if (tool == null) {
            return StepResult.failed("Iterator tool is not enabled: " + toolName);
        }

        Map<String, Object> argMappings = new LinkedHashMap<>(node.configMap("argMappings"));
        Object itemsMapping = argMappings.remove("items");
        List<?> items = resolveIteratorItems(itemsMapping, context);
        if (items == null) items = List.of();
        if (items.isEmpty()) {
            return StepResult.success(
                    Map.of(node.id(), Map.of("input", Map.of("items", List.of()), "output", List.of())),
                    null);
        }

        Object modelRefObj = runOptions == null ? null : runOptions.get("_runtime_modelRef");
        ModelRef modelRef = modelRefObj instanceof ModelRef m ? m : null;

        boolean parallel = !Boolean.FALSE.equals(node.config().get("parallel"));
        int maxConcurrency = 4;
        Object concObj = node.config().get("maxConcurrency");
        if (concObj instanceof Number n) maxConcurrency = Math.max(1, Math.min(n.intValue(), 16));
        boolean continueOnFail = Boolean.TRUE.equals(node.config().get("continueOnFail"));

        java.util.concurrent.Semaphore limiter = new java.util.concurrent.Semaphore(maxConcurrency);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> itemContext = new LinkedHashMap<>(context);
            itemContext.put(itemKey, item);
            itemContext.put("$item", item);
            CompletableFuture<Map<String, Object>> fut;
            if (parallel) {
                fut = CompletableFuture.supplyAsync(() -> {
                    try { limiter.acquire(); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return Map.of("error", "interrupted"); }
                    try {
                        return runInlineIteratorItem(tool, toolName, argMappings, itemContext, modelRef);
                    } finally { limiter.release(); }
                }, branchExecutor);
            } else {
                fut = CompletableFuture.completedFuture(runInlineIteratorItem(tool, toolName, argMappings, itemContext, modelRef));
            }
            futures.add(fut);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int i = 0;
        for (CompletableFuture<Map<String, Object>> f : futures) {
            if ("while".equals(loopMode)) {
                Map<String, Object> loopCtx = buildLoopContext(context, results, i, null);
                if (!booleanExpressionEvaluator.eval(exitCondition, loopCtx)) break;
            }
            try {
                Map<String, Object> itemResult = f.join();
                results.add(itemResult);
                if ("until".equals(loopMode)) {
                    Map<String, Object> loopCtx = buildLoopContext(context, results, i, itemResult);
                    if (booleanExpressionEvaluator.eval(exitCondition, loopCtx)) break;
                }
            } catch (Exception e) {
                if (continueOnFail) {
                    results.add(Map.of("error", e.getMessage() == null ? "iterator item failed" : e.getMessage()));
                } else {
                    return StepResult.failed("iterator item failed: " + e.getMessage());
                }
            }
            i++;
            if (i >= maxIterations) return StepResult.failed("iterator exceeded maxIterations=" + maxIterations);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("input", Map.of("items", items));
        envelope.put("output", results);
        if (collectOutput) envelope.put("results", results);
        return StepResult.success(Map.of(node.id(), envelope), null);
    }

    private Map<String, Object> buildLoopContext(Map<String, Object> context, List<?> results, int index, Object lastResult) {
        Map<String, Object> loopCtx = new LinkedHashMap<>(context);
        loopCtx.put("index", index);
        loopCtx.put("results", results);
        if (lastResult != null) loopCtx.put("lastResult", lastResult);
        return loopCtx;
    }

    private List<?> resolveIteratorItems(Object itemsMapping, Map<String, Object> context) {
        if (itemsMapping == null) return List.of();
        Object value = argMappingResolver.resolveOne(itemsMapping, context, key -> resolvePath(context, key));
        if (value instanceof List<?> list) return list;
        if (value == null) return null;
        return List.of(value);
    }

    private Map<String, Object> runInlineIteratorItem(AgentTool tool,
                                                      String toolName,
                                                      Map<String, Object> argMappings,
                                                      Map<String, Object> itemContext,
                                                      ModelRef modelRef) {
        ArgMappingResolver.ResolutionResult res = argMappingResolver.resolveAll(
                argMappings, itemContext, key -> resolvePath(itemContext, key));
        Map<String, Object> resolvedInput = new LinkedHashMap<>(res.resolved());
        coerceScalarArgs(resolvedInput, tool);
        if (modelRef != null) {
            for (ArgMappingResolver.DeferredArg deferred : res.deferred()) {
                if (resolvedInput.containsKey(deferred.argName())) continue;
                Object hint = deferred.mapping() == null ? null : deferred.mapping().expr();
                String hintExpr = hint instanceof String s ? s : null;
                llmArgResolver.resolve(toolName, deferred.argName(), itemContext, modelRef, hintExpr)
                        .ifPresent(resolved -> {
                            if (resolved.value() != null) resolvedInput.put(deferred.argName(), resolved.value());
                        });
            }
        }
        ToolExecutionService.ExecutionResult result = toolExecutionService.execute(tool, toJson(resolvedInput));
        if (!result.success()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", result.error());
            err.put("input", resolvedInput);
            return err;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("input", resolvedInput);
        ok.put("output", parseFlexibleBody(result.body()));
        return ok;
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
        Map<String, Object> argMappings = node.configMap("argMappings");
        boolean hasMappings = argMappings != null && !argMappings.isEmpty();

        Map<String, Object> payload;
        if (inputPath != null && !inputPath.isBlank()) {
            // Explicit inputKey: pull that subtree as the base payload.
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
        } else if (hasMappings) {
            // argMappings define exactly what the tool needs; do NOT also dump the full context,
            // or a missing mapping silently sends garbage downstream (e.g., context.message
            // colliding with a tool field, or required fields like ORD_ID never being set).
            payload = new LinkedHashMap<>();
        } else {
            // Legacy: no inputKey AND no argMappings — pass the entire context through.
            // Only safe for tools that explicitly accept the chain's free-form context.
            payload = new LinkedHashMap<>(context);
        }
        Map<String, Object> resolved = argMappingResolver.resolve(
                argMappings,
                context,
                key -> resolvePath(context, key));
        payload.putAll(resolved);
        return payload;
    }

    private Object resolvePath(Map<String, Object> context, String keyPath) {
        return PathResolver.resolvePath(context, keyPath, true);
    }

    private Object lookupKey(Map<?, ?> map, String part) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && String.valueOf(entry.getKey()).equalsIgnoreCase(part)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isStepRecord(Map<?, ?> map) {
        return map.size() == 2
                && (map.containsKey("input") || map.containsKey("Input"))
                && (map.containsKey("output") || map.containsKey("Output"));
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
                gate,
                runtimeTuningProperties.getToolIoLogMode(),
                runtimeTuningProperties.isProductionEnvironment()
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
        List<Map<String, Object>> variables = new ArrayList<>();
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
        Object varsObj = parsed.get("variables");
        if (varsObj instanceof List<?> varsList) {
            for (Object row : varsList) {
                if (row instanceof Map<?, ?> map) variables.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return new GraphModel(nodes, edges, variables);
    }

    private Map<String, Object> initializeVariables(List<Map<String, Object>> variables) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (variables == null) return out;
        for (Map<String, Object> row : variables) {
            if (row == null) continue;
            String name = row.get("name") == null ? null : String.valueOf(row.get("name"));
            if (name == null || name.isBlank()) continue;
            out.put(name, row.get("default"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonListMap(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            if (!(parsed instanceof List<?> list)) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) out.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
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

    private record GraphModel(List<NodeModel> nodes, List<EdgeModel> edges, List<Map<String, Object>> variables) {
        Map<String, NodeModel> nodesById() {
            Map<String, NodeModel> out = new LinkedHashMap<>();
            for (NodeModel node : nodes) out.put(node.id(), node);
            return out;
        }

        Map<String, List<EdgeModel>> outgoingEdges() {
            Map<String, List<EdgeModel>> out = new LinkedHashMap<>();
            for (NodeModel node : nodes) out.put(node.id(), new ArrayList<>());
            for (EdgeModel edge : edges) {
                out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
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

    private record EdgeModel(String from, String to, String condition, String kind) {
        static EdgeModel from(Map<String, Object> row) {
            String kind = row.get("kind") == null ? "success" : String.valueOf(row.get("kind"));
            String condition = row.get("condition") == null ? null : String.valueOf(row.get("condition"));
            Object from = row.get("from") != null ? row.get("from") : row.get("source");
            Object to = row.get("to") != null ? row.get("to") : row.get("target");
            return new EdgeModel(String.valueOf(from), String.valueOf(to), condition, kind);
        }
    }
}
