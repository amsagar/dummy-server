# Output envelopes — how to read a plugin's result in SpEL

The single biggest source of bugs in generated workflows. **One plugin wraps. The others don't.** Internalise this page before writing any `#{...}` that references an upstream activity's output.

## The rule, in one sentence

`CodeExecPlugin` wraps its return in `{success, output, stdout, stderr}` — access the actual value via `#var.output.<field>`. **Every other plugin returns its body directly** — access via `#var.<field>` (no `.output` prefix).

## Per-plugin cheatsheet

The authoritative source is [`doc/plugins.json`](../doc/plugins.json). This is the human-readable summary — they must agree.

| Plugin | Wraps? | Reading the result | Returns |
|---|---|---|---|
| `CodeExecPlugin` | ✅ Yes | `#result.output.<field>` | `{success, output, stdout, stderr}` |
| `AgentToolPlugin` | ❌ No | `#result.<field>` (depends on the specific tool) | Parsed JSON body, or raw string |
| `HttpRequestPlugin` | ❌ No | `#result.status`, `#result.body`, `#result.headers` | `{status, headers, body}` |
| `McpToolPlugin` | ❌ No | `#result` (it IS a string) | `String` |
| `AiChatPlugin` | ❌ No | `#result` (it IS a string) | `String` |
| `SkillToolPlugin` | ❌ No | `#result.skillContent`, `#result.files` | `{name, description, skillContent, files}` |

## Worked examples

### CodeExec accumulator (wrapped)

```json
{
  "id": "prepareItems",
  "type": "tool",
  "pluginName": "CodeExecPlugin",
  "properties": {
    "language": "java",
    "code": "return java.util.Map.of(\"sortedItems\", input.get(\"items\"));"
  },
  "outputVariables": [{ "name": "prep", "javaClass": "java.util.Map" }]
}
```

Read it as:

```
#prep.output.sortedItems   ✅ correct
#prep.sortedItems          ❌ null — forgot the .output wrapper
#prep.output               ✅ the entire returned Map
#prep.success              ✅ boolean — was the code successful?
```

### Agent tool (raw)

```json
{
  "id": "fetchOrder",
  "type": "tool",
  "pluginName": "AgentToolPlugin",
  "properties": { "toolName": "Get_OrderID", "input": "#{ {'ORD_ID': #orderId} }" },
  "outputVariables": [{ "name": "order", "javaClass": "java.util.Map" }]
}
```

Read it as:

```
#order.OrderType    ✅ correct — top-level field on the raw response
#order.output.OrderType   ❌ null — there is no envelope
```

### Decision-table tool (raw, but watch the special output shape)

```json
{
  "id": "evaluate",
  "type": "tool",
  "pluginName": "AgentToolPlugin",
  "properties": { "toolName": "decisionTableEvaluate",
                  "input": "#{ {'tableName': 'Leg Sequences', 'inputs': {'actualSequence': #legData.output.actualSequence}} }" },
  "outputVariables": [{ "name": "legSequenceResult", "javaClass": "java.util.Map" }]
}
```

`decisionTableEvaluate` returns `{matched, matchedRows, outputs}`. **Output columns live under `.outputs` (plural).**

```
#legSequenceResult.matched                       ✅ boolean
#legSequenceResult.outputs.journeyType           ✅ the column value
#legSequenceResult.output.journeyType            ❌ doesn't exist
#legSequenceResult.journeyType                   ❌ doesn't exist
```

See [`references/decision-tables.md`](decision-tables.md) for the full contract.

### HTTP request (raw)

```json
{ "id": "callApi", "type": "tool", "pluginName": "HttpRequestPlugin",
  "properties": { "url": "https://example.com/api", "method": "GET" },
  "outputVariables": [{ "name": "apiResult", "javaClass": "java.util.Map" }] }
```

```
#apiResult.status            ✅ HTTP status code (int)
#apiResult.body              ✅ response body as a string
#apiResult.headers["Content-Type"]   ✅ headers map
```

**Note:** non-2xx responses are returned, not thrown. Use a route activity (or a transition condition) to branch on `#apiResult.status`.

## Assembling end-node `result` expressions

End-node `result` properties usually mix CodeExec outputs and AgentTool outputs. Apply the rule per variable:

```spel
#{ {
  'orderId':      #orderId,
  'journeyType':  #legData.output.journeyType,       // CodeExec → .output
  'rules':        #legSequenceResult.outputs.rule,   // decisionTableEvaluate → .outputs (plural!)
  'aggregate':    #accumulator.output                 // CodeExec accumulator — the full returned value
} }
```

See [`end-node-results.md`](end-node-results.md) for the full pattern.

## The mistake the validator now catches

`WorkflowJsonValidator` runs `result_expression_envelope_mismatch` against every `#{...}` expression. If you write `#myCodeExecVar.foo` (forgetting `.output`) or `#myAgentToolVar.output.foo` (adding a wrapper that isn't there), the validator rejects the workflow with the corrective hint from this page. The same check runs on save via `ProcessDefService.save()` — broken envelopes cannot be persisted.
