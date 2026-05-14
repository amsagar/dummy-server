# End-node result expression — assembling the final output

A workflow's end activity (an `isEnd: true` `route` activity) typically declares a `properties.result` SpEL expression. The engine evaluates that expression when the run finishes and persists the value to `process_inst.result_json`. The dashboard, the API, and any downstream consumer read from that JSON.

This is the highest-leverage SpEL string in the whole workflow — get it wrong and the run looks "completed" but the dashboard renders empty. The bug that motivated this document existed for weeks: `journeyType` was read from the decision-table tool's response using `.output.journeyType` (CodeExec envelope syntax) instead of `.outputs.journeyType` (the tool's actual top-level key). The new `result_expression_envelope_mismatch` and `tool_output_shape_mismatch` validator rules now reject expressions that make this kind of mistake.

## Shape

```json
{
  "id": "end",
  "name": "End",
  "type": "route",
  "isEnd": true,
  "properties": {
    "result": "#{ { ... map literal ... } }"
  }
}
```

The result must be a `#{...}` SpEL template. Most commonly an inline map literal: `{ 'key1': value1, 'key2': value2 }`.

## Build it field-by-field with the envelope rule

For each value, apply the per-plugin envelope rule (see [`output-envelopes.md`](output-envelopes.md)):

- **CodeExec-produced variable** → access via `.output.<field>`.
- **AgentTool / Http / Mcp / AiChat / SkillTool-produced variable** → access directly, NO `.output` prefix.
- **`decisionTableEvaluate` tool's output** → fields under `.outputs.<column>` (plural).

## Worked example (the Validate Pods Order end-node, corrected)

```spel
#{ {
  'orderId':           #orderId,
  'journeyType':       (#legData?.output?.journeyType ?: ''),
  'actualSequence':    (#legData?.output?.actualSequence ?: {}),
  'legSequence':       #legSequenceResult,
  'serviceability':    (#serviceabilityResults?.output ?: {}),
  'containerAvailability': (#containerAvailabilityResults?.output ?: {})
} }
```

Mapping:

| Key | Source variable | Producing plugin | Path |
|---|---|---|---|
| `orderId` | `#orderId` | top-level workflow variable | direct |
| `journeyType` | `#legData` | CodeExecPlugin | `.output.journeyType` |
| `actualSequence` | `#legData` | CodeExecPlugin | `.output.actualSequence` |
| `legSequence` | `#legSequenceResult` | AgentToolPlugin (decisionTableEvaluate) | direct (whole envelope; downstream consumer reads `.matched`, `.outputs`, `.matchedRows`) |
| `serviceability` | `#serviceabilityResults` | CodeExecPlugin (accumulator) | `.output` |
| `containerAvailability` | `#containerAvailabilityResults` | CodeExecPlugin (accumulator) | `.output` |

The pre-fix expression was:

```spel
'journeyType': (#legSequenceResult?.output?.journeyType ?: '')
```

That's wrong on two axes simultaneously:
1. `legSequenceResult` is an AgentToolPlugin variable (raw), not a CodeExec variable — there is no `.output` envelope.
2. Even if there were, `decisionTableEvaluate` puts column values under `.outputs` (plural), not `.output`.

## Empty-collection fallbacks

Be careful with `?: {}`. In SpEL, `{}` is an empty **list**, not a map. If your consumer expects a map, use `{:}` instead. If it expects a list, `{}` is fine.

```spel
'maybeList':  (#someVar?.output ?: {})    // empty list fallback
'maybeMap':   (#someVar?.output ?: {:})   // empty map fallback
```

## Null safety

Always prefix downstream accesses with `?.` so a half-failed run still emits a parseable result rather than a SpEL evaluation error:

```spel
'firstRule': #legSequenceResult?.matchedRows?[0]?.ruleId
```

If `legSequenceResult` is null, this short-circuits to null instead of throwing.

## Multiple end-nodes

A workflow can have more than one `isEnd: true` route. Only the result expression on the activity the run actually reaches is evaluated. If you ship a "happy path" end node and an "error" end node, give them different `result` shapes so the consumer can distinguish.

## The mistake the validator now catches

`WorkflowJsonValidator.result_expression_envelope_mismatch` walks every property string including the end-node's `result`. For each `#var.<field>` it scans, it cross-references the producing activity's plugin against `doc/plugins.json` and rejects the draft if the envelope path is wrong. The fix recipe from `doc/validator-codes.json` is appended to the resume prompt so the builder LLM sees exactly what to change.
