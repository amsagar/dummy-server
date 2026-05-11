package com.pods.agent.workflow.engine.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level process definition. This is the canonical engine-readable shape
 * the React Flow board emits and {@code process_def.xpdl_json} stores.
 *
 * The {@code activitiesById} and {@code outgoingTransitions} maps are derived
 * during construction so the engine never has to re-scan the lists at runtime.
 */
public record ProcessDefinition(
        String id,
        String name,
        String version,
        String packageId,
        String description,
        List<VariableSpec> variables,
        List<ActivityDef> activities,
        List<TransitionDef> transitions,
        Map<String, ActivityDef> activitiesById,
        Map<String, List<TransitionDef>> outgoingTransitions,
        Map<String, List<TransitionDef>> incomingTransitions,
        ActivityDef startActivity
) {
    public static ProcessDefinition build(
            String id,
            String name,
            String version,
            String packageId,
            String description,
            List<VariableSpec> variables,
            List<ActivityDef> activities,
            List<TransitionDef> transitions
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ProcessDefinition.id must be non-blank");
        }
        List<VariableSpec> v = variables == null ? List.of() : List.copyOf(variables);
        List<ActivityDef> a = activities == null ? List.of() : List.copyOf(activities);
        List<TransitionDef> t = transitions == null ? List.of() : List.copyOf(transitions);

        Map<String, ActivityDef> byId = new LinkedHashMap<>();
        for (ActivityDef act : a) {
            if (byId.put(act.id(), act) != null) {
                throw new IllegalArgumentException("duplicate activity id: " + act.id());
            }
        }
        Map<String, List<TransitionDef>> outgoing = new LinkedHashMap<>();
        Map<String, List<TransitionDef>> incoming = new LinkedHashMap<>();
        for (TransitionDef tr : t) {
            if (!byId.containsKey(tr.fromActivityId())) {
                throw new IllegalArgumentException("transition " + tr.id()
                        + " from unknown activity " + tr.fromActivityId());
            }
            if (!byId.containsKey(tr.toActivityId())) {
                throw new IllegalArgumentException("transition " + tr.id()
                        + " to unknown activity " + tr.toActivityId());
            }
            outgoing.computeIfAbsent(tr.fromActivityId(), k -> new java.util.ArrayList<>()).add(tr);
            incoming.computeIfAbsent(tr.toActivityId(), k -> new java.util.ArrayList<>()).add(tr);
        }
        outgoing.replaceAll((k, list) -> List.copyOf(list));
        incoming.replaceAll((k, list) -> List.copyOf(list));

        ActivityDef start = a.stream().filter(ActivityDef::isStart).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "ProcessDefinition " + id + " has no start activity"));

        return new ProcessDefinition(
                id, name, version, packageId, description,
                v, a, t,
                Collections.unmodifiableMap(byId),
                Collections.unmodifiableMap(outgoing),
                Collections.unmodifiableMap(incoming),
                start
        );
    }

    public List<TransitionDef> outgoing(String activityId) {
        return outgoingTransitions.getOrDefault(activityId, List.of());
    }

    public List<TransitionDef> incoming(String activityId) {
        return incomingTransitions.getOrDefault(activityId, List.of());
    }

    public ActivityDef requireActivity(String id) {
        ActivityDef a = activitiesById.get(id);
        if (a == null) {
            throw new IllegalArgumentException("unknown activity id: " + id);
        }
        return a;
    }
}
