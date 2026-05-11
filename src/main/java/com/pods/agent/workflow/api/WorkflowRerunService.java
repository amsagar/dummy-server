package com.pods.agent.workflow.api;

import com.pods.agent.workflow.engine.WorkflowManager;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.engine.domain.TransitionDef;
import com.pods.agent.workflow.persistence.ActivityInstRepository;
import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.ActivityPinRepository;
import com.pods.agent.workflow.persistence.ActivityPinRow;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRepository;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements "re-run from this node": pins every upstream activity's output
 * from a source run, then starts a new run that fast-replays the upstream
 * pins and executes the target activity (and everything downstream) for
 * real. Pins are cleared after the rerun terminates so they don't pollute
 * subsequent normal runs.
 */
@Service
@Slf4j
public class WorkflowRerunService {

    private final ProcessInstRepository processInstRepo;
    private final ActivityInstRepository activityInstRepo;
    private final ActivityPinRepository pinRepo;
    private final WorkflowVariableRepository variableRepo;
    private final ProcessDefService defService;
    private final WorkflowManager workflowManager;
    private final ObjectMapper objectMapper;

    public WorkflowRerunService(ProcessInstRepository processInstRepo,
                                ActivityInstRepository activityInstRepo,
                                ActivityPinRepository pinRepo,
                                WorkflowVariableRepository variableRepo,
                                ProcessDefService defService,
                                WorkflowManager workflowManager,
                                ObjectMapper objectMapper) {
        this.processInstRepo = processInstRepo;
        this.activityInstRepo = activityInstRepo;
        this.pinRepo = pinRepo;
        this.variableRepo = variableRepo;
        this.defService = defService;
        this.workflowManager = workflowManager;
        this.objectMapper = objectMapper;
    }

    public Optional<WorkflowManager.StartResult> rerunFrom(String sourceInstanceId,
                                                           String fromActivityDefId,
                                                           String requesterId) {
        ProcessInstRow source = processInstRepo.findById(sourceInstanceId).orElse(null);
        if (source == null) return Optional.empty();
        ProcessDefinition def = defService.loadDomainById(source.defId()).orElse(null);
        if (def == null) return Optional.empty();
        try {
            def.requireActivity(fromActivityDefId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        // Compute every activity upstream of (and not including) the target.
        Set<String> upstream = upstreamActivities(def, fromActivityDefId);
        upstream.remove(fromActivityDefId);

        // Latest activity_inst per activity_def_id from the source run.
        Map<String, ActivityInstRow> latest = new LinkedHashMap<>();
        for (ActivityInstRow row : activityInstRepo.findByInstId(sourceInstanceId)) {
            ActivityInstRow prev = latest.get(row.activityDefId());
            if (prev == null
                    || (row.startedAt() != null && (prev.startedAt() == null || row.startedAt() >= prev.startedAt()))) {
                latest.put(row.activityDefId(), row);
            }
        }

        // Clear any stale pins for this def before installing the new set.
        for (ActivityPinRow p : pinRepo.findByDef(def.id())) {
            pinRepo.delete(p.defId(), p.activityDefId());
        }

        // Pin upstream outputs.
        long now = Instant.now().toEpochMilli();
        int pinned = 0;
        for (String defId : upstream) {
            ActivityInstRow row = latest.get(defId);
            if (row == null || row.outputSnapshot() == null) continue;
            pinRepo.upsert(new ActivityPinRow(
                    UUID.randomUUID().toString(),
                    def.id(),
                    defId,
                    row.outputSnapshot(),
                    now,
                    now,
                    requesterId));
            pinned++;
        }
        log.info("[WorkflowRerunService] pinned {} upstream activities for rerun-from {} on def {}",
                pinned, fromActivityDefId, def.id());

        // Re-hydrate source variable scope as initial variables.
        Map<String, Object> initialVariables = new LinkedHashMap<>();
        for (WorkflowVariableRow row : variableRepo.findByInstAndScope(sourceInstanceId, sourceInstanceId)) {
            initialVariables.put(row.name(), deserializeValue(row.valueJson()));
        }

        try {
            WorkflowManager.StartResult result =
                    workflowManager.startProcess(def, initialVariables, requesterId);
            return Optional.of(result);
        } finally {
            // Always clear pins so subsequent normal runs aren't polluted.
            for (ActivityPinRow p : pinRepo.findByDef(def.id())) {
                pinRepo.delete(p.defId(), p.activityDefId());
            }
        }
    }

    /**
     * Set of activity_def_ids that can reach {@code target} by following
     * outgoing transitions. Includes {@code target} itself (callers strip it
     * if they don't want it).
     */
    private Set<String> upstreamActivities(ProcessDefinition def, String target) {
        Set<String> upstream = new HashSet<>();
        upstream.add(target);
        Deque<String> q = new ArrayDeque<>();
        q.add(target);
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (TransitionDef t : def.transitions()) {
                if (cur.equals(t.toActivityId()) && upstream.add(t.fromActivityId())) {
                    q.add(t.fromActivityId());
                }
            }
        }
        return upstream;
    }

    private Object deserializeValue(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (RuntimeException e) {
            return json;
        }
    }

    /** Children created by sub-flow activities; used by the drill-in endpoint. */
    public List<ProcessInstRow> findChildren(String parentInstanceId) {
        return processInstRepo.findChildren(parentInstanceId);
    }
}
