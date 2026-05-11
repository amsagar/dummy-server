# Activity types

The `ActivityDef` constructor enforces a closed enum. Only these lowercase strings are accepted:

| `type`         | Constant in code                       | Dispatcher behavior                                                                  |
|----------------|----------------------------------------|---------------------------------------------------------------------------------------|
| `normal`       | `WorkflowActivity.TYPE_NORMAL`         | Auto-completes immediately today (Phase 1). Reserved for human-in-the-loop later.     |
| `tool`         | `WorkflowActivity.TYPE_TOOL`           | Resolves `pluginName` to an `ApplicationPlugin` and runs `plugin.execute(properties)`.|
| `route`        | `WorkflowActivity.TYPE_ROUTE`          | No work; lets `RouteResolver` evaluate outgoing transition conditions.                |
| `subflow`      | `WorkflowActivity.TYPE_SUBFLOW`        | Invokes a child `ProcessDefinition`. Reserved (Phase 5); avoid unless asked.          |
| `foreach`      | `WorkflowActivity.TYPE_FOREACH`        | Iterates a list. See loop section.                                                    |
| `while`        | `WorkflowActivity.TYPE_WHILE`          | Condition-driven loop. See loop section.                                              |
| `batch`        | `WorkflowActivity.TYPE_BATCH`          | Chunked list processing. See loop section.                                            |
| `ai_reasoning` | `WorkflowActivity.TYPE_AI_REASONING`   | The **only** node type the runtime is allowed to invoke the LLM from. See AI section. |

Anything else throws:
`ActivityDef.type must be one of normal/tool/route/subflow/foreach/while/batch/ai_reasoning, got: <value>`.

## Common BPMN aliases — never emit these

The engine does **not** speak BPMN. The materializer maps a few aliases defensively as a guardrail, but the canonical names are the only ones you should produce.

| BPMN alias                   | Canonical equivalent          |
|------------------------------|-------------------------------|
| `startEvent`, `start`        | `route` (with `isStart: true`)|
| `endEvent`, `end`, `terminateEndEvent` | `route` (with `isEnd: true`)|
| `task`, `serviceTask`, `scriptTask`, `businessRuleTask` | `tool` |
| `userTask`, `manualTask`, `humanTask` | `normal` |
| `exclusiveGateway`, `inclusiveGateway`, `eventBasedGateway` | `route` |
| `parallelGateway`            | `route` (set `andJoin: true` on the join side) |
| `subProcess`, `callActivity` | `subflow` |

## Activity field semantics

```
ActivityDto {
  id                  : unique non-blank string within the workflow
  name                : display label
  type                : "normal" | "tool" | "route" | "subflow" | "foreach" | "while" | "batch" | "ai_reasoning"
  pluginName          : ApplicationPlugin bean name; required for type=tool, null otherwise (also null for ai_reasoning)
  properties          : Map<String,Object>; passed to the plugin; values may be #{...}
  deadlineExpression  : ISO-8601 duration like "PT60S"; null = no deadline
  isStart             : exactly one activity has true
  isEnd               : one or more activities have true
  subflowDefId        : process_def id of the child workflow (subflow only)
  subflowInputs       : map { childVarName -> parentExpression }
  subflowOutputs      : map { parentVarName -> childVarName }
  outputVariables     : VariableSpec[] declaring where the plugin's return value lands
  andJoin             : true => wait for ALL incoming arrivals; false (default) => fire on first
}
```

### `route` activities

Used for entry, exit, and branching. Leave `pluginName: null`. Branching is implemented by the **outgoing transitions' `condition` expressions**, not by code in the route itself. A route always succeeds.

```json
{ "id": "start", "name": "Start", "type": "route", "isStart": true, "isEnd": false, "properties": {}, "andJoin": false }
```

### `tool` activities

Calls one of the registered plugins. `pluginName` must match the bean's class name (e.g. `AgentToolPlugin`, `HttpRequestPlugin`). The `properties` map is what the plugin reads.

```json
{
  "id": "callApi",
  "name": "Call API",
  "type": "tool",
  "pluginName": "HttpRequestPlugin",
  "properties": {
    "method": "GET",
    "url": "#{'https://api.example.com/items/' + #itemId}",
    "timeoutMs": 15000
  },
  "outputVariables": [{ "name": "apiResponse", "javaClass": "java.util.Map", "defaultExpression": null, "required": false }]
}
```

The plugin's return value is written into the **first** declared output variable (if any).

### `normal` activities

Manual / human task placeholder. Auto-completes in Phase 1. Use for steps that, in a future HITL flow, would gate on a person.

```json
{ "id": "review", "name": "Manual review", "type": "normal", "properties": {}, "andJoin": false }
```

### `subflow` activities

Reserved. Sets `subflowDefId` to a child `process_def` id and uses `subflowInputs`/`subflowOutputs` to map variables across the boundary. Avoid producing these unless the user explicitly requested a multi-process orchestration.

### `foreach` / `while` / `batch` activities

Loop activities. Each dispatch:

1. Reads the iteration state from the variable scope (or seeds it on the first call).
2. Updates the loop variables (`itemVar`/`indexVar` for `foreach`, `batchVar`/`batchIndexVar` for `batch`, condition evaluation for `while`).
3. Writes a continuation flag `__loop_continue_<activityId>` into the scope (`true` → keep looping, `false` → exhausted).
4. Returns success.

The router uses the continuation flag automatically: when it's `false`, the unconditional `ON_SUCCESS` body edge is skipped and the `ON_NO_MATCH`/default edge fires instead. So the canonical wiring is:

| Edge | From → To              | trigger        | condition |
|------|-------------------------|----------------|-----------|
| body | loop → bodyEntry        | `ON_SUCCESS`   | `null`    |
| exit | loop → next             | `ON_NO_MATCH`  | `null`    (set `isDefault: true`) |
| back | bodyExit → loop         | `ON_SUCCESS`   | `null`    |

Required loop properties:

- `foreach`: `collection` (SpEL list expression), `itemVar`, `indexVar`, `maxIterations`.
- `while`:   `condition` (SpEL boolean), `maxIterations`.
- `batch`:   `collection`, `batchSize`, `batchVar`, `batchIndexVar`, `maxIterations`.

`maxIterations` is **mandatory** and validated at materialization. Choose a generous but bounded value (e.g. `1000`) to prevent runaway loops.

### `ai_reasoning` activities

The **only** node type the deterministic runtime is allowed to invoke the LLM from. Use it when the source step in the chat turn was a judgement step the chat agent reasoned about (classification, summarization, fraud verdict, deduping, ranking, intent extraction).

Required `properties.prompt`. Optional `system`, `invokeWhen` (SecureSpel boolean — when present and false, the node short-circuits without calling the LLM), and per-node model override via `providerID` / `modelID` (or the shorthand `model: "provider/modelId"`). When omitted, the node uses the run-scope `__providerID` / `__modelID` variables that the caller (chat / API) sets at run start.

Output is always a `Map`:

```json
{
  "text":         "<assistant message>",
  "finishReason": "stop|length|...",
  "usage":        { "promptTokens": 123, "completionTokens": 45, "totalTokens": 168 },
  "model":        { "providerID": "anthropic", "modelID": "claude-sonnet-4-7" },
  "skipped":      false
}
```

Downstream uses **`#yourVar.text`** for the assistant message; never `#yourVar` directly.

```json
{
  "id": "fraudReview",
  "name": "Classify cart fraud risk",
  "type": "ai_reasoning",
  "properties": {
    "system":     "You are a cart fraud reviewer. Reply with exactly LOW, MEDIUM, or HIGH.",
    "prompt":     "#{ 'Cart total: ' + #cartTotal + '. Items: ' + #items }",
    "invokeWhen": "#cartTotal > 500",
    "providerID": "openai",
    "modelID":    "gpt-4o-mini"
  },
  "outputVariables": [
    { "name": "fraudVerdict", "javaClass": "java.util.Map", "required": false }
  ]
}
```

Validation (`validateWorkflowStructure`):
- `properties.prompt` must be a non-blank string → fails with `ai_reasoning_missing_prompt:<id>`.
- `outputVariables` must contain at least one entry → fails with `ai_reasoning_missing_output_variable:<id>`.
- `pluginName` must be `null` → fails with `ai_reasoning_plugin_not_allowed:<id>`.
