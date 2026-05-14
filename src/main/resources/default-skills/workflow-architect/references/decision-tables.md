# Decision tables — calling `decisionTableEvaluate` and reading its result

DMN-style decision tables are evaluated via the `decisionTableEvaluate` agent tool. This tool has a quirky output shape that has been a frequent source of bugs — read this page before wiring one in.

## Input — what `decisionTableEvaluate` accepts

```json
{
  "tableName": "Leg Sequences",
  "inputs": {
    "actualSequence": ["NEW", "WRT", "RDL", "FPU"]
  }
}
```

- `tableName` (required, string) — exact name of the stored decision table.
- `inputs` (optional, object) — map of input-column-name → value. Missing inputs default to `null` during FEEL evaluation. Required input columns missing here will fire a tool-side failure.

In a workflow, the typical activity looks like:

```json
{
  "id": "evaluateLegSequence",
  "type": "tool",
  "pluginName": "AgentToolPlugin",
  "properties": {
    "toolName": "decisionTableEvaluate",
    "input": "#{ {'tableName': 'Leg Sequences', 'inputs': {'actualSequence': #legData.output.actualSequence}} }"
  },
  "outputVariables": [{ "name": "legSequenceResult", "javaClass": "java.util.Map" }]
}
```

Note the SpEL inline-map for the input: keys are quoted with single quotes; nested maps use the same syntax.

## Output — the shape that bites everyone

`decisionTableEvaluate` returns this structure **directly** (no envelope — it's an `AgentToolPlugin` tool):

```json
{
  "matched": true,
  "matchedRows": [
    { "ruleIndex": 1, "ruleId": "rule-02", "outputs": { "journeyType": "Local", "result": "Valid Storage Warehouse sequence" } }
  ],
  "outputs": {
    "journeyType": "Local",
    "result": "Valid Storage Warehouse sequence"
  }
}
```

Three top-level fields:
- **`matched`** — boolean, did at least one rule fire?
- **`matchedRows`** — array of the rules that matched (each carries its own `outputs` map and `ruleId`).
- **`outputs`** — the merged map of output column values across all matched rules. For `Collect` hit-policy, later rules override earlier; for `FIRST` and `UNIQUE`, there is at most one row.

## Access patterns

```
#legSequenceResult.matched                            ✅ boolean
#legSequenceResult.outputs.journeyType                ✅ value of the journeyType output column
#legSequenceResult.matchedRows[0].ruleId              ✅ id of the first matched rule
#legSequenceResult.matchedRows[0].outputs.journeyType ✅ per-rule output (same as merged map for FIRST/UNIQUE)
```

```
#legSequenceResult.output.journeyType    ❌ there is NO 'output' (singular). Use 'outputs' (plural).
#legSequenceResult.journeyType           ❌ output columns are nested under .outputs, not at the top level.
#legSequenceResult.output.outputs.x      ❌ no wrapper — AgentToolPlugin returns raw.
```

## Common downstream pattern: foreach over `matchedRows`

When a decision table uses `Collect` hit-policy and returns multiple matched rules:

```json
{
  "id": "iterateMatchedRules",
  "type": "foreach",
  "properties": {
    "collection": "#{#legSequenceResult.matchedRows}",
    "itemVar": "currentRule",
    "indexVar": "currentRuleIndex",
    "maxIterations": 100
  }
}
```

In the loop body, access each rule's output via `#currentRule.outputs.<columnName>` and the rule id via `#currentRule.ruleId`.

## End-node result inclusion

When surfacing decision-table results to the dashboard, prefer the merged `outputs` map:

```spel
#{ {
  'journeyType':  #legSequenceResult.outputs.journeyType,
  'matchedRule':  #legSequenceResult.matchedRows[0].ruleId,
  'tableMatched': #legSequenceResult.matched
} }
```

## The mistake the validator now catches

`WorkflowJsonValidator` runs `tool_output_shape_mismatch` for any expression that reads `#var.<field>` where `var` is the output of an `AgentToolPlugin` activity whose `toolName` is listed in [`doc/tools.json`](../doc/tools.json). For `decisionTableEvaluate`, the validator rejects any access that doesn't use one of the documented accessors above and points the author at this page.

## Adding new agent tools

When you register a new agent tool with a stable output schema, add an entry to [`doc/tools.json`](../doc/tools.json) so downstream workflows can be validated. The schema and accessor entries become both the LLM's reference and the validator's source of truth.
