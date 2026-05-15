package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.RuleExecution;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a compiled rule domain's BPMN end-to-end and records the result.
 *
 * Flowable processes start asynchronously by default; we use a synchronous
 * deployment so that all our {@link org.flowable.engine.delegate.JavaDelegate}
 * service tasks run on the calling thread. This keeps debugging simple and
 * allows the caller to obtain the final variables in one shot.
 *
 * If the BPMN has not been deployed yet (cold start after restart), this
 * method will deploy {@link RuleDomain#getBpmnXml()} before starting.
 */
@Component
@Slf4j
public class BpmnRuntime {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final RuleExecutionRepository executionRepo;
    private final ObjectMapper objectMapper;

    /**
     * Bounded pool for fan-out execution of multiple rules in one turn. Sized
     * small (8 threads) because rules are typically I/O-bound on tool calls
     * and the per-rule BPMN is short. Naming threads helps debugging.
     */
    private final ExecutorService fanoutExecutor = Executors.newFixedThreadPool(8, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "rule-domain-runtime-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public BpmnRuntime(RuntimeService runtimeService,
                       RepositoryService repositoryService,
                       HistoryService historyService,
                       RuleExecutionRepository executionRepo,
                       ObjectMapper objectMapper) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.executionRepo = executionRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Async fan-out wrapper around {@link #execute}. Each rule in a domain-group
     * fan-out gets its own thread; the orchestrator waits on a join of all
     * futures. The underlying Flowable execution is still synchronous on the
     * worker thread (delegates run inline) — we just hide the wait behind a
     * future so the caller can run N rules in parallel.
     */
    public CompletableFuture<ExecutionOutcome> executeAsync(RuleDomain domain,
                                                            Map<String, Object> inputs,
                                                            String sessionId,
                                                            String turnId,
                                                            boolean fromCacheHit) {
        return CompletableFuture.supplyAsync(
                () -> execute(domain, inputs, sessionId, turnId, fromCacheHit),
                fanoutExecutor);
    }

    /**
     * Deploy the BPMN if it isn't already known to Flowable, returning the
     * latest deployment id. Idempotent — Flowable de-duplicates on resource
     * content via the {@code enableDuplicateFiltering} flag.
     */
    public synchronized String ensureDeployed(RuleDomain domain) {
        ProcessDefinition existing = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(domain.getFlowableProcKey())
                .latestVersion()
                .singleResult();
        if (existing != null) {
            return existing.getDeploymentId();
        }
        String resourceName = "rule-domain-" + domain.getId() + ".bpmn20.xml";
        Deployment dep = repositoryService.createDeployment()
                .name("rule-domain:" + domain.getSkillName() + ":" + domain.getIntentLabel())
                .addString(resourceName, domain.getBpmnXml())
                .enableDuplicateFiltering()
                .deploy();
        log.info("Deployed BPMN for domain {} as deployment {} (key={})",
                domain.getId(), dep.getId(), domain.getFlowableProcKey());
        return dep.getId();
    }

    /**
     * Execute the compiled BPMN against the given input map. Inputs become
     * top-level process variables; the BPMN's compiler-emitted final variable
     * {@code result} is read back and returned as the outcome's outputs.
     *
     * Always inserts a row into {@code agent.rule_executions} — success or
     * failure — so the admin UI can show the run history.
     */
    public ExecutionOutcome execute(RuleDomain domain,
                                    Map<String, Object> inputs,
                                    String sessionId,
                                    String turnId,
                                    boolean fromCacheHit) {
        ensureDeployed(domain);
        long start = System.currentTimeMillis();

        Map<String, Object> variables = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        // Propagate the chat turn id into process variables so cross-thread
        // emitters (BpmnTraceListener, etc.) can resolve the right SSE stream.
        if (turnId != null && !turnId.isBlank()) {
            variables.putIfAbsent("_turnId", turnId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            variables.putIfAbsent("_sessionId", sessionId);
        }
        String businessKey = "rd-" + UUID.randomUUID();

        ProcessInstance pi;
        try {
            pi = runtimeService.startProcessInstanceByKey(
                    domain.getFlowableProcKey(),
                    businessKey,
                    variables);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Failed to start BPMN for domain {}: {}", domain.getId(), ex.getMessage());
            persist(domain, null, sessionId, turnId, inputs, null, false, ex.getMessage(), latency);
            return ExecutionOutcome.failed(domain.getId(), null, ex.getMessage(), latency);
        }

        // Synchronous execution: when all delegates run on the caller thread,
        // the process instance is already ended by the time startProcessInstanceByKey
        // returns. Read final history.
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getProcessInstanceId())
                .singleResult();

        Map<String, Object> historicVars = readHistoricVariables(pi.getProcessInstanceId());
        // Variables may come back as Jackson JsonNode (when stored via
        // BpmnVariables.set for Flowable's JSON type). Unwrap to plain
        // Java types so the summarizer + serializer downstream don't have
        // to know about Jackson 2.x vs 3.x.
        Object resultRaw = BpmnVariables.toJavaNative(historicVars.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultVar = resultRaw instanceof Map<?, ?> r
                ? (Map<String, Object>) r
                : flattenedHistoricVars(historicVars);

        Object failedToolVar = historicVars.get("_failedTool");
        boolean processAborted = historic != null && historic.getEndTime() == null;
        boolean tookErrorPath = failedToolVar != null;
        boolean failed = processAborted || tookErrorPath;

        String error;
        if (tookErrorPath) {
            error = "Tool execution failed: " + failedToolVar;
        } else if (processAborted) {
            error = historic == null ? null : historic.getDeleteReason();
        } else {
            error = null;
        }
        long latency = System.currentTimeMillis() - start;

        String outputsJson = safeJson(resultVar);
        persist(domain, pi.getProcessInstanceId(), sessionId, turnId, inputs,
                outputsJson, !failed, error, latency);

        if (failed) {
            Map<String, String> errorMeta = null;
            if (failedToolVar != null) {
                errorMeta = Map.of("failedTool", failedToolVar.toString());
            }
            return ExecutionOutcome.failed(domain.getId(), pi.getProcessInstanceId(), error, latency, errorMeta);
        }
        return ExecutionOutcome.handled(domain.getId(), pi.getProcessInstanceId(),
                resultVar, fromCacheHit, latency);
    }

    private Map<String, Object> readHistoricVariables(String procInstanceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(procInstanceId)
                .list()
                .forEach(v -> out.put(v.getVariableName(), v.getValue()));
        return out;
    }

    /** Like {@code new LinkedHashMap<>(historicVars)} but with every value
     *  passed through {@link BpmnVariables#toJavaNative} so any leftover
     *  Jackson 2.x JsonNode trees become plain Java structures. */
    private static Map<String, Object> flattenedHistoricVars(Map<String, Object> historicVars) {
        Map<String, Object> out = new LinkedHashMap<>(historicVars.size());
        for (Map.Entry<String, Object> e : historicVars.entrySet()) {
            out.put(e.getKey(), BpmnVariables.toJavaNative(e.getValue()));
        }
        return out;
    }

    private void persist(RuleDomain domain,
                         String procInstanceId,
                         String sessionId,
                         String turnId,
                         Map<String, Object> inputs,
                         String outputsJson,
                         boolean success,
                         String error,
                         long latencyMs) {
        try {
            executionRepo.save(RuleExecution.builder()
                    .domainId(domain.getId())
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .flowableProcId(procInstanceId == null ? "" : procInstanceId)
                    .inputsJson(safeJson(inputs))
                    .outputsJson(outputsJson)
                    .success(success)
                    .errorMessage(error)
                    .latencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE))
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to persist rule_execution row: {}", ex.getMessage());
        }
    }

    private String safeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "\"<serialization failed: " + ex.getMessage() + ">\"";
        }
    }
}
