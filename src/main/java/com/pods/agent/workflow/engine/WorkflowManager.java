package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.engine.domain.ProcessState;
import com.pods.agent.workflow.engine.domain.VariableSpec;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;
import com.pods.agent.workflow.metadata.WorkflowMetadataService;
import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRepository;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Public entry point to the workflow engine. Mirrors the surface of Joget's
 * {@code WorkflowManager} (start / abort / query) but is Spring-native and
 * does not depend on Shark or DODS.
 *
 * <p>Phase 1: synchronous {@link #startProcess} blocks until the process
 * reaches a terminal state. Phase 5 will add an async variant that returns
 * an instance id immediately and runs the executor on a background pool.
 */
@Service
@Slf4j
public class WorkflowManager {

    private final ProcessExecutor executor;
    private final AuditTrailManager audit;
    private final EnginePersistence persistence;
    private final ProcessInstRepository processInstRepo;
    private final WorkflowVariableRepository variableRepo;
    private final ObjectMapper objectMapper;
    /**
     * Phase C: rolling aggregates on {@code process_def}. Optional so the
     * engine still works in test contexts that don't wire the metadata layer.
     */
    private final WorkflowMetadataService metadataService;
    private final ExecutorService asyncPool = Executors.newVirtualThreadPerTaskExecutor();

    public WorkflowManager(ProcessExecutor executor,
                           AuditTrailManager audit,
                           EnginePersistence persistence,
                           ProcessInstRepository processInstRepo,
                           WorkflowVariableRepository variableRepo,
                           ObjectMapper objectMapper,
                           org.springframework.beans.factory.ObjectProvider<WorkflowMetadataService> metadataServiceProvider) {
        this.executor = executor;
        this.audit = audit;
        this.persistence = persistence;
        this.processInstRepo = processInstRepo;
        this.variableRepo = variableRepo;
        this.objectMapper = objectMapper;
        this.metadataService = metadataServiceProvider == null ? null : metadataServiceProvider.getIfAvailable();
    }

    /**
     * Async start: returns the instance id immediately and runs the executor
     * on a virtual thread. Useful for long-running workflows where the HTTP
     * client should not wait. Status is queryable via the run controller.
     */
    public String startProcessAsync(ProcessDefinition definition,
                                    Map<String, Object> initialVariables,
                                    String requesterId) {
        if (definition == null) {
            throw new IllegalArgumentException("definition required");
        }
        String instanceId = UUID.randomUUID().toString();
        long startedAt = Instant.now().toEpochMilli();

        VariableScope scope = new VariableScope(instanceId);
        applyDefaults(scope, definition);
        if (initialVariables != null) {
            scope.setAll(initialVariables);
        }

        persistence.persistProcessStart(new ProcessInstRow(
                instanceId,
                definition.id(),
                ProcessState.OPEN_RUNNING.wire(),
                startedAt,
                null,
                requesterId,
                null,
                null,
                null,
                null));

        CompletableFuture.runAsync(() -> {
            ExecutionContext ctx = new ExecutionContext(instanceId, definition, scope, requesterId);
            ProcessState terminal;
            String errorClass = null;
            String errorMessage = null;
            try {
                terminal = executor.run(ctx);
            } catch (RuntimeException e) {
                log.error("[WorkflowManager] async uncaught error in process {}", instanceId, e);
                audit.record(instanceId,
                        AuditTrailManager.Action.PROCESS_TERMINATED,
                        Map.of("reason", "uncaught: " + e.getClass().getSimpleName(),
                                "message", String.valueOf(e.getMessage())));
                terminal = ProcessState.CLOSED_TERMINATED;
                errorClass = "UNCAUGHT";
                errorMessage = e.getMessage();
            }
            if (terminal == ProcessState.OPEN_SUSPENDED) {
                persistence.persistProcessSuspend(instanceId);
                return;
            }
            long endedAt = Instant.now().toEpochMilli();
            persistence.persistProcessEnd(instanceId,
                    terminal.wire(),
                    endedAt,
                    errorClass,
                    errorMessage);
            recordCompletionMetadata(definition.id(), instanceId, terminal, startedAt, endedAt);
        }, asyncPool);

        return instanceId;
    }

    public StartResult startProcess(ProcessDefinition definition,
                                    Map<String, Object> initialVariables,
                                    String requesterId) {
        if (definition == null) {
            throw new IllegalArgumentException("definition required");
        }
        String instanceId = UUID.randomUUID().toString();
        long startedAt = Instant.now().toEpochMilli();

        VariableScope scope = new VariableScope(instanceId);
        applyDefaults(scope, definition);
        if (initialVariables != null) {
            scope.setAll(initialVariables);
        }

        persistence.persistProcessStart(new ProcessInstRow(
                instanceId,
                definition.id(),
                ProcessState.OPEN_RUNNING.wire(),
                startedAt,
                null,
                requesterId,
                null,
                null,
                null,
                null));

        for (VariableSpec v : definition.variables()) {
            if (v.required() && !scope.has(v.name())) {
                String msg = "required variable missing: " + v.name();
                long endedAt = Instant.now().toEpochMilli();
                audit.record(instanceId,
                        AuditTrailManager.Action.PROCESS_TERMINATED,
                        Map.of("reason", msg));
                persistence.persistProcessEnd(instanceId,
                        ProcessState.CLOSED_TERMINATED.wire(),
                        endedAt,
                        "VALIDATION",
                        msg);
                return new StartResult(instanceId, ProcessState.CLOSED_TERMINATED, msg);
            }
        }

        ExecutionContext ctx = new ExecutionContext(instanceId, definition, scope, requesterId);
        ProcessState terminal;
        String errorClass = null;
        String errorMessage = null;
        try {
            terminal = executor.run(ctx);
        } catch (RuntimeException e) {
            log.error("[WorkflowManager] uncaught error in process {}", instanceId, e);
            audit.record(instanceId,
                    AuditTrailManager.Action.PROCESS_TERMINATED,
                    Map.of("reason", "uncaught: " + e.getClass().getSimpleName(),
                            "message", String.valueOf(e.getMessage())));
            terminal = ProcessState.CLOSED_TERMINATED;
            errorClass = "UNCAUGHT";
            errorMessage = e.getMessage();
        }

        if (terminal == ProcessState.OPEN_SUSPENDED) {
            persistence.persistProcessSuspend(instanceId);
            return new StartResult(instanceId, terminal, errorMessage);
        }
        long endedAt = Instant.now().toEpochMilli();
        persistence.persistProcessEnd(instanceId,
                terminal.wire(),
                endedAt,
                errorClass,
                errorMessage);
        recordCompletionMetadata(definition.id(), instanceId, terminal, startedAt, endedAt);
        return new StartResult(instanceId, terminal, errorMessage);
    }

    private void applyDefaults(VariableScope scope, ProcessDefinition definition) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (VariableSpec v : definition.variables()) {
            if (v.defaultExpression() == null || v.defaultExpression().isBlank()) {
                continue;
            }
            SecureSpelEvaluator.Result r =
                    SecureSpelEvaluator.evaluate(v.defaultExpression(), bindings);
            if (r.ok()) {
                Object coerced = coerceDefaultToDeclaredType(v, r.value());
                scope.set(v.name(), coerced);
                bindings.put(v.name(), coerced);
            } else {
                log.warn("[WorkflowManager] default expression failed for {}: {}",
                        v.name(), r.error());
            }
        }
    }

    /**
     * Rescues a small set of common SpEL-vs-declared-type mismatches in
     * {@code defaultExpression} so the architect (or a hand-written workflow)
     * can't dead-end every run with a single typo.
     *
     * <p>The most frequent offender: SpEL {@code {}} parses as an empty
     * {@code List}, not an empty {@code Map}. The empty-map literal is
     * {@code {:}}. When a variable declares {@code java.util.Map} but the
     * evaluated default came back as an empty {@code Collection} (or null),
     * we substitute an empty {@link LinkedHashMap}. The same convenience
     * applies in reverse for {@code java.util.List}.
     *
     * <p>Anything that isn't an obvious empty-collection mismatch is returned
     * as-is — we never silently coerce non-empty values, because that would
     * mask real type bugs. The skill / architect prompt is updated separately
     * to teach the canonical syntax.
     */
    private Object coerceDefaultToDeclaredType(VariableSpec spec, Object value) {
        String declared = spec.javaClass();
        if (declared == null || declared.isBlank()) {
            return value;
        }
        boolean wantMap = "java.util.Map".equals(declared) || "java.util.HashMap".equals(declared)
                || "java.util.LinkedHashMap".equals(declared);
        boolean wantList = "java.util.List".equals(declared) || "java.util.ArrayList".equals(declared);

        if (wantMap) {
            if (value == null) {
                return new LinkedHashMap<>();
            }
            if (value instanceof java.util.Collection<?> c && c.isEmpty()) {
                log.warn("[WorkflowManager] variable '{}' declared java.util.Map but defaultExpression '{}' evaluated to an empty {}; "
                                + "substituting empty Map. Hint: SpEL '{{}}' is an empty List — use '{{:}}' for an empty Map, or null.",
                        spec.name(), spec.defaultExpression(), value.getClass().getSimpleName());
                return new LinkedHashMap<>();
            }
        }
        if (wantList) {
            if (value == null) {
                return new java.util.ArrayList<>();
            }
            // Map → List would imply real intent mismatch; don't auto-coerce that direction.
        }
        return value;
    }

    /**
     * True mid-flow resume: re-hydrate variable scope, worklist, and join
     * state from the {@code process_inst} checkpoint, then continue the
     * executor from where it was when the JVM (or pod) last died.
     *
     * <p>Returns the terminal state. Returns null if the instance can't be
     * found or has no checkpoint (already completed or never had one).
     */
    public StartResult resumeProcess(String instanceId,
                                     ProcessDefinition definition,
                                     String requesterId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId required");
        }
        if (definition == null) {
            throw new IllegalArgumentException("definition required");
        }
        ProcessInstRow inst = processInstRepo.findById(instanceId).orElse(null);
        if (inst == null) {
            return null;
        }
        if (ProcessState.fromWire(inst.state()).isClosed()) {
            log.info("[WorkflowManager] resume: instance {} already closed ({})",
                    instanceId, inst.state());
            return new StartResult(instanceId, ProcessState.fromWire(inst.state()),
                    "instance already closed");
        }
        EnginePersistence.Checkpoint cp = persistence.loadCheckpoint(instanceId);
        if (cp == null || cp.worklistJson() == null) {
            log.info("[WorkflowManager] resume: no checkpoint for instance {}; nothing to do", instanceId);
            return new StartResult(instanceId, ProcessState.fromWire(inst.state()),
                    "no checkpoint to resume from");
        }

        // Re-hydrate variable scope.
        VariableScope scope = new VariableScope(instanceId);
        for (WorkflowVariableRow row : variableRepo.findByInstAndScope(instanceId, instanceId)) {
            scope.set(row.name(), deserializeVariable(row.valueJson()));
        }

        // Re-hydrate worklist + join state.
        java.util.List<String> worklistIds = executor.deserializeWorklist(cp.worklistJson());
        JoinCoordinator.Snapshot joinSnapshot = executor.deserializeJoin(cp.joinStateJson());

        ExecutionContext ctx = new ExecutionContext(instanceId, definition, scope, requesterId);
        ProcessState terminal;
        String errorClass = null;
        String errorMessage = null;
        try {
            terminal = executor.resume(ctx, worklistIds, joinSnapshot);
        } catch (RuntimeException e) {
            log.error("[WorkflowManager] resume uncaught error in process {}", instanceId, e);
            audit.record(instanceId,
                    AuditTrailManager.Action.PROCESS_TERMINATED,
                    Map.of("reason", "uncaught during resume: " + e.getClass().getSimpleName(),
                            "message", String.valueOf(e.getMessage())));
            terminal = ProcessState.CLOSED_TERMINATED;
            errorClass = "UNCAUGHT";
            errorMessage = e.getMessage();
        }
        if (terminal == ProcessState.OPEN_SUSPENDED) {
            persistence.persistProcessSuspend(instanceId);
            return new StartResult(instanceId, terminal, errorMessage);
        }
        long endedAt = Instant.now().toEpochMilli();
        persistence.persistProcessEnd(instanceId,
                terminal.wire(),
                endedAt,
                errorClass,
                errorMessage);
        // Use the originally-recorded started_at so resumed runs report
        // wall-clock latency, not just the resume window.
        long startedAt = inst.startedAt() == null ? endedAt : inst.startedAt();
        recordCompletionMetadata(definition.id(), instanceId, terminal, startedAt, endedAt);
        return new StartResult(instanceId, terminal, errorMessage);
    }

    /**
     * Best-effort metadata write. Wrapped in a try/catch so a metadata
     * failure (e.g. pgvector missing, transient DB hiccup) can never poison
     * the engine's terminal-state path.
     */
    private void recordCompletionMetadata(String defId,
                                          String instanceId,
                                          ProcessState terminal,
                                          long startedAt,
                                          long endedAt) {
        if (metadataService == null || defId == null) {
            return;
        }
        try {
            metadataService.recordRunCompletion(defId, instanceId, terminal, startedAt, endedAt);
        } catch (RuntimeException e) {
            log.warn("[WorkflowManager] metadata aggregation failed for inst={} def={}: {}",
                    instanceId, defId, e.getMessage());
        }
    }

    private Object deserializeVariable(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (RuntimeException e) {
            log.warn("[WorkflowManager] could not deserialize variable: {}", e.getMessage());
            return json;
        }
    }

    public record StartResult(String instanceId, ProcessState state, String errorMessage) {
        public boolean isCompleted() {
            return state == ProcessState.CLOSED_COMPLETED;
        }
    }
}
