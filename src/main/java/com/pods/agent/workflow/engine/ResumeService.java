package com.pods.agent.workflow.engine;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRepository;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Re-runs a previously executed process instance.
 *
 * <p><b>Phase 5 scope: REPLAY semantics, not mid-execution checkpoint resume.</b>
 * The instance's persisted variables (from {@code workflow_variable}) are
 * read back and used as the initial variables of a brand-new run. The new
 * run starts from the start activity, not from where the previous one
 * stopped. This is useful for "re-run with same inputs after a fix" but is
 * not the same as a true checkpoint resume.
 *
 * <p>True mid-flow checkpoint resume requires the executor to (a) checkpoint
 * the worklist + JoinCoordinator at every transition, and (b) restore them
 * on resume. That's a separate piece of work; documented in
 * {@link WorkflowManager#startProcessAsync} comments as future scope.
 */
@Service
@Slf4j
public class ResumeService {

    private final ProcessInstRepository processInstRepo;
    private final WorkflowVariableRepository variableRepo;
    private final ProcessDefService defService;
    private final WorkflowManager workflowManager;
    private final ObjectMapper objectMapper;

    public ResumeService(ProcessInstRepository processInstRepo,
                         WorkflowVariableRepository variableRepo,
                         ProcessDefService defService,
                         WorkflowManager workflowManager,
                         ObjectMapper objectMapper) {
        this.processInstRepo = processInstRepo;
        this.variableRepo = variableRepo;
        this.defService = defService;
        this.workflowManager = workflowManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Replay the given instance: read its variable snapshot and start a new
     * instance of the same process definition with those variables as the
     * initial map.
     *
     * @return the new instance id, or {@code null} if the source instance or
     *         its definition can't be located.
     */
    public String replay(String sourceInstanceId, String requesterId) {
        ProcessInstRow source = processInstRepo.findById(sourceInstanceId).orElse(null);
        if (source == null) {
            log.warn("[ResumeService] replay: source instance {} not found", sourceInstanceId);
            return null;
        }
        ProcessDefinition def = defService.loadDomainById(source.defId()).orElse(null);
        if (def == null) {
            log.warn("[ResumeService] replay: definition {} not found", source.defId());
            return null;
        }
        Map<String, Object> initialVariables = readVariableScope(sourceInstanceId);
        WorkflowManager.StartResult result = workflowManager.startProcess(
                def, initialVariables, requesterId);
        log.info("[ResumeService] replayed {} as new instance {} (state={})",
                sourceInstanceId, result.instanceId(), result.state().wire());
        return result.instanceId();
    }

    /**
     * Re-hydrate the variable scope of the source instance from the
     * persistent {@code workflow_variable} table.
     */
    private Map<String, Object> readVariableScope(String instanceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (WorkflowVariableRow row : variableRepo.findByInstAndScope(instanceId, instanceId)) {
            out.put(row.name(), deserializeValue(row.valueJson()));
        }
        return out;
    }

    private Object deserializeValue(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (RuntimeException e) {
            log.warn("[ResumeService] could not deserialize variable: {}", e.getMessage());
            return json;
        }
    }
}
