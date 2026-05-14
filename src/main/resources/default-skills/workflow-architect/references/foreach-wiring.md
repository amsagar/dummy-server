# Foreach / while / batch — wiring three transitions, not one

The single most common shape mistake in generated loops is forgetting the back-edge or the exit edge. The engine needs **three** transitions to drive a loop:

```
foreach ──ON_SUCCESS──▶ body[0] ──ON_SUCCESS──▶ ... ──ON_SUCCESS──▶ body[N-1]
   ▲                                                                    │
   └──────────────────────── ON_SUCCESS (back-edge) ────────────────────┘
   │
   └──ON_NO_MATCH (priority>=100, isDefault=true)──▶ next-activity
```

Authoritative source: [`doc/transition-triggers.json`](../doc/transition-triggers.json).

## The three required edges

For every `foreach` / `while` / `batch` activity:

1. **Body-forward** — `ON_SUCCESS`, from the loop activity to the first body activity.
2. **Body-back** — `ON_SUCCESS`, from the LAST body activity back to the loop activity. Without this the loop runs exactly one iteration.
3. **Loop-exit** — `ON_NO_MATCH`, from the loop activity to the activity following the loop. Must have `isDefault: true` and `priority >= 100` so it fires only after the engine sets `__loop_continue_<id>=false` on exhaustion.

## How the engine drives iteration

When dispatched, a foreach activity returns one of two shapes (see [`ActivityDispatcher.dispatchForeach`](../../../../src/main/java/com/pods/agent/workflow/engine/ActivityDispatcher.java)):

```
{ continue: true, index: <0-based>, size: <collection-size> }   // an iteration step
{ continue: false, size: <size> }                                // exhaustion
```

It also writes three synthetic variables you must NOT shadow:

- `__loop_continue_<activityId>` — boolean; routing reads this to decide whether to fire the body-forward edge or the loop-exit edge.
- `__loop_index_<activityId>` — current 0-based iteration counter.
- `__loop_guard_<activityId>` — cross-checked against `properties.maxIterations` to prevent runaway loops.

The item is exposed under `properties.itemVar` (default `item`) and the index under `properties.indexVar` (default `index`).

## Worked example

```json
{
  "id": "iterateLegs",
  "type": "foreach",
  "properties": {
    "collection": "#{#legData.output.legLines}",
    "itemVar": "currentLegLine",
    "indexVar": "currentLegIndex",
    "maxIterations": 1000
  }
}
```

Transitions:

```json
[
  { "id": "t-iter-body",  "fromActivityId": "iterateLegs", "toActivityId": "callServiceability",
    "trigger": "ON_SUCCESS" },
  { "id": "t-body-accum", "fromActivityId": "callServiceability", "toActivityId": "accumulateServiceability",
    "trigger": "ON_SUCCESS" },
  { "id": "t-accum-back", "fromActivityId": "accumulateServiceability", "toActivityId": "iterateLegs",
    "trigger": "ON_SUCCESS" },
  { "id": "t-iter-exit",  "fromActivityId": "iterateLegs", "toActivityId": "prepareContainerCheck",
    "trigger": "ON_NO_MATCH", "priority": 100, "isDefault": true }
]
```

Read this as: loop fires `t-iter-body` while there are items; the body finishes with `t-accum-back` back to the foreach; once exhausted, the loop's only firable outgoing edge is the priority-100 default `t-iter-exit`.

## The accumulator pattern

Foreach bodies typically need to collect per-iteration results into a single output variable. Pattern: append-to-list inside a CodeExecPlugin activity, reading the previous accumulator value via SpEL.

```json
{
  "id": "accumulateServiceability",
  "type": "tool",
  "pluginName": "CodeExecPlugin",
  "properties": {
    "language": "java",
    "code": "java.util.List<Object> acc = new java.util.ArrayList<>(); if (input.get(\"acc\") instanceof java.util.List<?> existing) { for (Object o : existing) acc.add(o); } acc.add(input.get(\"current\")); return acc;",
    "input": "#{ {'acc': #serviceabilityResults?.output, 'current': #serviceabilityResult} }"
  },
  "outputVariables": [
    { "name": "serviceabilityResults", "javaClass": "java.util.List" }
  ]
}
```

Two details to internalise:
- The accumulator's previous value lives at `#serviceabilityResults?.output` because the activity itself produces the variable; on the first iteration it's null so the safe-navigation returns null and the Java code defaults to a fresh list.
- The activity's output variable shadows its previous value on each iteration — that's how the engine "remembers" the running total.

See [`templates/foreach-accumulate.json`](../templates/foreach-accumulate.json) for a complete copy-paste example.

## While loops

Same three-edge pattern, but the loop activity has no `collection`/`itemVar`/`indexVar` — instead `properties.condition` is a SpEL boolean evaluated each iteration. There's no per-item variable, just whatever your body sets.

## Batch loops

Same three-edge pattern. Body sees `batchItems` (a sub-list of size `batchSize`) and `batchIndex` (the batch number) instead of `item` and `index`. Useful for chunking large collections through a bulk API.

## The mistake the validator now catches

`WorkflowJsonValidator.foreach_wiring_incomplete` checks that every loop activity (`foreach` / `while` / `batch`) has the three required edges. Drafts missing any one get rejected with a concrete fix recipe from `doc/validator-codes.json`.
