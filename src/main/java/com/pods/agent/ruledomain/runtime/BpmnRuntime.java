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
        @SuppressWarnings("unchecked")
        Map<String, Object> resultVar = historicVars.get("result") instanceof Map<?, ?> r
                ? (Map<String, Object>) r
                : new LinkedHashMap<>(historicVars);

        boolean failed = historic != null && historic.getEndTime() == null;
        String error = historic == null ? null : historic.getDeleteReason();
        long latency = System.currentTimeMillis() - start;

        String outputsJson = safeJson(resultVar);
        String inputsJson = safeJson(inputs);
        persist(domain, pi.getProcessInstanceId(), sessionId, turnId, inputs,
                outputsJson, !failed, error, latency);

        if (failed) {
            return ExecutionOutcome.failed(domain.getId(), pi.getProcessInstanceId(), error, latency);
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
