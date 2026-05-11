package com.pods.agent.workflow.api;

import com.pods.agent.workflow.api.dto.ProcessDefDto;
import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ActivityErrorPolicy;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.engine.domain.TransitionDef;
import com.pods.agent.workflow.engine.domain.TransitionTrigger;
import com.pods.agent.workflow.engine.domain.VariableSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts between the wire {@link ProcessDefDto} and the engine
 * {@link ProcessDefinition}. {@link ProcessDefinition#build} performs all
 * structural validation (duplicate ids, dangling transitions, missing start)
 * — this mapper is just the field-by-field translation.
 */
public final class ProcessDefinitionMapper {

    private ProcessDefinitionMapper() {}

    public static ProcessDefinition toDomain(ProcessDefDto dto) {
        Objects.requireNonNull(dto, "dto");
        List<VariableSpec> variables = dto.variables() == null ? List.of()
                : dto.variables().stream().map(ProcessDefinitionMapper::toVariableSpec).toList();
        List<ActivityDef> activities = dto.activities() == null ? List.of()
                : dto.activities().stream().map(ProcessDefinitionMapper::toActivityDef).toList();
        List<TransitionDef> transitions = dto.transitions() == null ? List.of()
                : dto.transitions().stream().map(ProcessDefinitionMapper::toTransitionDef).toList();
        return ProcessDefinition.build(
                dto.id(),
                dto.name(),
                dto.version(),
                dto.packageId(),
                dto.description(),
                variables,
                activities,
                transitions);
    }

    public static ProcessDefDto fromDomain(ProcessDefinition def) {
        Objects.requireNonNull(def, "def");
        return new ProcessDefDto(
                def.id(),
                def.name(),
                def.version(),
                def.packageId(),
                def.description(),
                def.variables().stream().map(ProcessDefinitionMapper::fromVariableSpec).toList(),
                def.activities().stream().map(ProcessDefinitionMapper::fromActivityDef).toList(),
                def.transitions().stream().map(ProcessDefinitionMapper::fromTransitionDef).toList()
        );
    }

    private static VariableSpec toVariableSpec(ProcessDefDto.VariableSpecDto v) {
        return new VariableSpec(
                v.name(),
                v.javaClass(),
                v.defaultExpression(),
                Boolean.TRUE.equals(v.required()));
    }

    private static ProcessDefDto.VariableSpecDto fromVariableSpec(VariableSpec v) {
        return new ProcessDefDto.VariableSpecDto(
                v.name(), v.javaClass(), v.defaultExpression(), v.required());
    }

    private static ActivityDef toActivityDef(ProcessDefDto.ActivityDto a) {
        List<VariableSpec> outputVars = a.outputVariables() == null ? List.of()
                : a.outputVariables().stream().map(ProcessDefinitionMapper::toVariableSpec).toList();
        return new ActivityDef(
                a.id(),
                a.name(),
                a.type(),
                a.pluginName(),
                a.properties() == null ? Map.of() : a.properties(),
                a.inputSchema() == null ? Map.of() : a.inputSchema(),
                a.outputSchema() == null ? Map.of() : a.outputSchema(),
                a.deadlineExpression(),
                Boolean.TRUE.equals(a.isStart()),
                Boolean.TRUE.equals(a.isEnd()),
                a.subflowDefId(),
                a.subflowInputs(),
                a.subflowOutputs(),
                outputVars,
                Boolean.TRUE.equals(a.andJoin()),
                toErrorPolicy(a.errorPolicy()));
    }

    private static ProcessDefDto.ActivityDto fromActivityDef(ActivityDef a) {
        return new ProcessDefDto.ActivityDto(
                a.id(),
                a.name(),
                a.type(),
                a.pluginName(),
                a.properties(),
                a.inputSchema(),
                a.outputSchema(),
                a.deadlineExpression(),
                a.isStart(),
                a.isEnd(),
                a.subflowDefId(),
                a.subflowInputs(),
                a.subflowOutputs(),
                a.outputVariables().stream().map(ProcessDefinitionMapper::fromVariableSpec).toList(),
                a.andJoin(),
                fromErrorPolicy(a.errorPolicy()));
    }

    private static TransitionDef toTransitionDef(ProcessDefDto.TransitionDto t) {
        return new TransitionDef(
                t.id(),
                t.fromActivityId(),
                t.toActivityId(),
                t.condition(),
                Boolean.TRUE.equals(t.isErrorEdge()),
                t.matchesErrorClass(),
                parseTrigger(t.trigger()),
                t.priority(),
                Boolean.TRUE.equals(t.isDefault()));
    }

    private static ProcessDefDto.TransitionDto fromTransitionDef(TransitionDef t) {
        return new ProcessDefDto.TransitionDto(
                t.id(),
                t.fromActivityId(),
                t.toActivityId(),
                t.condition(),
                t.isErrorEdge(),
                t.matchesErrorClass(),
                t.trigger() == null ? null : t.trigger().name(),
                t.priority(),
                t.isDefault());
    }

    private static ActivityErrorPolicy toErrorPolicy(ProcessDefDto.ErrorPolicyDto p) {
        if (p == null) return ActivityErrorPolicy.defaults();
        return new ActivityErrorPolicy(
                p.retryCount() == null ? 0 : p.retryCount(),
                p.backoffMs() == null ? 0L : p.backoffMs(),
                p.timeoutMs(),
                Boolean.TRUE.equals(p.failFast()),
                Boolean.TRUE.equals(p.continueOnError()));
    }

    private static ProcessDefDto.ErrorPolicyDto fromErrorPolicy(ActivityErrorPolicy p) {
        if (p == null) return null;
        return new ProcessDefDto.ErrorPolicyDto(
                p.retryCount(),
                p.backoffMs(),
                p.timeoutMs(),
                p.failFast(),
                p.continueOnError());
    }

    private static TransitionTrigger parseTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) return null;
        try {
            return TransitionTrigger.valueOf(trigger.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
