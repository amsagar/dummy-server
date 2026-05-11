---
name: workflow-architect
description: "Use this skill when generating or refining a reusable ProcessDefDto workflow JSON for the pods-ov-agent engine, OR when you (the chat agent) need to drop reasoning notes that will help the architect convert this turn into a workflow later. Defines the typed execution-log input contract, the architect_note write tool, the wire schema, canonical activity types (normal/tool/route/subflow/foreach/while/batch), available plugins, expression syntax, and quality rules the engine validates."
---

# Workflow Architect Skill

You are designing a `ProcessDefDto` JSON artifact that will be saved by `ProcessDefService.save()` and executed by `ProcessExecutor` / `ActivityDispatcher`. The engine is **strict**: invalid types, dangling transitions, or missing start activities cause the proposal to be rejected outright. Follow the rules below to the letter.

> **Two readers of this skill.** Most of this file is for the **Workflow Architect** subagent — the LLM that converts a finished chat turn into a workflow JSON. The section [For the chat agent: when to drop architect notes](#for-the-chat-agent-when-to-drop-architect-notes) is for the **chat agent** itself: it explains the one write tool you have during the turn (`architect_note`) and when to use it so the architect downstream produces a sharper workflow.

---

## 🛑 READ THIS FIRST — Zero-tolerance prohibitions

Four failure modes have produced broken workflows in the past. Each is **non-negotiable**: violations cause the proposal to be rejected at review and embarrass the system in the UI.

### 1. NO ghost / "Load …" / "Setup …" / "Init …" activities

You will be tempted to start the workflow with a friendly-sounding bootstrap step like:

- `Load Workflow Architect` (toolName: `skill` / `workflow-architect`)
- `Load Order Validation Skill`
- `Initialize Session` / `Setup Context` / `Prepare Request`
- `Validate Input` / `Normalize Params` (as a standalone CodeExecPlugin step)
- `Log Result` / `Audit Outcome` / `Track Metric` at the end

**All of these are hallucinations.** The chat turn never executed them. They are not real tools. The engine does not need them. Emit zero of them.

**Mechanical filter** — before you write ANY `AgentToolPlugin` activity, do this in your head:

```
1. Open `summary.toolNames` from the execution log.
2. For each AgentToolPlugin activity you are about to emit:
     if activity.properties.toolName ∉ summary.toolNames:
         DELETE the activity. Do not emit it. Do not rationalize it.
3. For each activity whose `name` starts with one of these prefixes
   (case-insensitive): "Load ", "Init", "Setup", "Prepare", "Bootstrap",
   "Warmup", "Validate Input", "Normalize", "Log Result", "Audit ", "Track":
     STOP. This is almost certainly a ghost. The only legitimate exceptions
     are when the turn actually called a tool whose name semantically
     matches (which will already be visible in `summary.toolNames` and
     thus survive step 2).
```

If after running that filter you are left with zero activities besides `start` → `end`, that is the correct workflow. A workflow that does nothing is better than a workflow that does fictional work.

### 2. NO invented tool names

Every `AgentToolPlugin` activity's `toolName` MUST be a literal string that appears in `summary.toolNames`. No paraphrasing, no pluralizing, no "obvious" variants. If the turn called `getCart`, you may emit `toolName: "getCart"` — never `getCarts`, `fetchCart`, `loadCart`, or `cart`.

### 3. NO Python / JavaScript / TypeScript in CodeExecPlugin

Java only. Other languages are temporarily disabled in the runtime — emitting them guarantees a failed run.

### 4. PRESERVE the exact input shape from `steps[i].input`

This is where the architect most often loses fidelity. When the chat turn called a tool, the execution log captures the **exact input map** that was sent:

```json
{ "type": "tool", "tool": "decisionTableEvaluate",
  "input": { "tableName": "leg-sequence", "inputs": { "orderId": 600030447, ... } } }
```

The architect's job is to **emit the same shape**, with literals replaced by variable references. You do NOT get to flatten, simplify, rename, or paraphrase. The runtime validates the resulting map against the tool's contract — if you drop a required key (`tableName`, `inputs`, `query`, `id`, …) the run fails on the first dispatch with `"<key> is required"`.

**Mechanical filter — for every `AgentToolPlugin` activity:**

```
1. Find the matching step in execution-log.steps where step.tool == this.toolName.
   (If multiple — they all share the same shape; use any one.)
2. Read step.input. It is a Map<String, Object>.
3. Build the activity's properties.input as a SecureSpel inline-map literal
   that reproduces THAT MAP'S TOP-LEVEL KEYS exactly, replacing run-specific
   values with variable references:

       SecureSpel inline-map literal:  #{ {'key1': <expr>, 'key2': <expr>, ...} }

4. Do NOT collapse a multi-key input to a single `#{#someVar}`. That is the
   #1 way this rule gets violated.
```

**Worked example — `decisionTableEvaluate`** (real tool, requires `tableName` + `inputs`):

The execution log shows:
```json
{ "tool": "decisionTableEvaluate",
  "input": { "tableName": "leg-sequence", "inputs": { "originCity": "Houston", "destinationCity": "Dallas" } } }
```

✅ **Correct activity properties:**
```json
{
  "toolName": "decisionTableEvaluate",
  "input": "#{ {'tableName': 'leg-sequence', 'inputs': #order} }"
}
```

❌ **Wrong — collapses to single var, drops `tableName`:**
```json
{ "toolName": "decisionTableEvaluate", "input": "#{#order}" }
```
This fails immediately: `IllegalStateException: tableName is required`. The tool received a map with no `tableName` key because you handed it the order map instead of the wrapped envelope.

**Other common multi-key shapes you must preserve verbatim:**

| Tool family | Required keys |
|---|---|
| `decisionTableEvaluate` | `tableName`, `inputs` |
| Most "search" / "query" tools | `query` (often plus `limit`, `filter`) |
| Update / mutation tools | `id` plus `body` or per-field updates |
| Anything taking pagination | `offset`, `limit` alongside the filter |

If the original `step.input` has N top-level keys, your `properties.input` literal must have N top-level keys. Never N=1 unless the log shows N=1.

---

If any of the above four rules is unclear, default to emitting fewer activities, not more, and ALWAYS copy the tool's input shape verbatim from the execution log. The rest of this file fleshes out the schema and patterns, but **these four rules dominate everything else**.

---

## Execution log input contract

Before writing any JSON, the architect MUST read the turn's execution log:

```
.pods-agent/workflow/execution-log-<turnId>.json
```

The path is supplied in the user prompt under "Execution log file". The document shape:

```json
{
  "executionId": "<turnId>",
  "sessionId":   "...",
  "userPrompt":  "...",
  "modelRef":    { "providerID": "...", "modelID": "..." },
  "startedAt":   1778..., "completedAt": 1778...,
  "assistantResponse": "...",
  "steps": [
    { "seq": 0, "ts": ..., "type": "state_transition", "state": "PLAN",      "reason": "turn-start" },
    { "seq": 1, "ts": ..., "type": "tool",             "tool": "getCart",    "input": {...}, "output": {...}, "status": "success", "elapsedMs": 412 },
    { "seq": 2, "ts": ..., "type": "architect_note",   "note": "loop products: for each item in #products" },
    { "seq": 3, "ts": ..., "type": "tool",             "tool": "getProduct", "input": {"id": 1}, "output": {...}, "status": "success", "elapsedMs": 173 },
    { "seq": 4, "ts": ..., "type": "tool",             "tool": "getProduct", "input": {"id": 2}, "output": {...}, "status": "success", "elapsedMs": 168 },
    ...
  ],
  "summary": {
    "toolNames":   ["getCart","getProduct"],
    "stepCount":   12,
    "stepsByType": { "tool": 9, "state_transition": 2, "architect_note": 1 }
  }
}
```

Step type taxonomy currently emitted at chat time:

| `type`             | Meaning |
|--------------------|---------|
| `tool`             | A tool call. Has `tool`, `input`, `output`, `status`, `elapsedMs`. The `tool` value matches a name in `summary.toolNames`. |
| `state_transition` | Planner FSM transition (PLAN → ACT → SYNTHESIZE → DONE). Useful for understanding the chat agent's macro-flow but not usually replayed in the workflow. |
| `architect_note`   | A free-form note dropped by the chat agent. Treat these as **structural hints** — they typically mark where the chat agent decided to loop, branch, fan-out, or call for AI reasoning. |
| `reasoning`        | Captured `<think>` tokens from the model. Background context only — never replay. |
| `other`            | Fallback bucket for unrecognised event types. Skim, don't replay. |

Reserved (will appear in Phase C+): `loop`, `condition`, `parallel`, `ai_reasoning` — emitted by the deterministic engine when materialized workflows execute. The architect doesn't see these at first-run time because the chat agent has no native loop/condition semantics; loops/conditions are inferred from **patterns** (the same tool called repeatedly with different inputs) and from **architect_note** annotations.

### Reading patterns

- **Repeated tool with varying input** → emit a `foreach`/`while`/`batch` activity.
- **Single tool followed by a transformation step** → emit a `tool` activity then a `tool`/`route` activity.
- **`architect_note` containing the literal word "loop"** → boundary marker for a loop block. Use it to decide what to wrap.
- **`architect_note` containing "condition" or "if"** → branch / `route` activity decision.
- **`architect_note` containing "parallel" or "fan-out"** → AND-split using `andJoin: true` on the join target.
- **`architect_note` containing "ai_reasoning"** → reserve a (future) `ai_reasoning` node here; for now use an `AiChatPlugin` tool activity and mention the intent in `name`/`description`.

---

---

## CRITICAL — Activity types are a closed enum

Every activity's `type` field MUST be **exactly one** of these lowercase strings:

```
"normal"   "tool"   "route"   "subflow"   "foreach"   "while"   "batch"   "ai_reasoning"
```

Anything else fails validation in the `ActivityDef` constructor.

**Forbidden — never emit these BPMN-style names:**
`startEvent`, `endEvent`, `task`, `userTask`, `serviceTask`, `scriptTask`,
`businessRuleTask`, `manualTask`, `humanTask`, `exclusiveGateway`,
`inclusiveGateway`, `parallelGateway`, `eventBasedGateway`,
`subProcess`, `callActivity`, `terminateEndEvent`, `none`, `event`.

**When to use each canonical type:**

| Type           | Use for                                                                                  |
|----------------|------------------------------------------------------------------------------------------|
| `route`        | Start node, end node, branching/decision nodes, AND-splits, joins. No work executed.     |
| `tool`         | Any unit of execution — calls a registered plugin via `pluginName`.                      |
| `normal`       | Manual / human task placeholder. Auto-completes today; reserved for HITL.                |
| `subflow`      | Invoke a child `ProcessDefinition`. Reserved (Phase 5); avoid unless asked.              |
| `foreach`      | Loop array items. Use `properties.collection` and `itemVar/indexVar`.                    |
| `while`        | Condition-driven loop. Use `properties.condition` and optional guardrail settings.       |
| `batch`        | Chunked list processing. Use `properties.collection` and `batchSize`.                    |
| `ai_reasoning` | The **only** node type allowed to invoke the LLM at runtime. Use ONLY when the step needs judgement (classification, summarization, fraud verdict, ranking). See [AI_REASONING nodes](#ai_reasoning-nodes-the-only-llm-call-the-runtime-is-allowed-to-make) below. |

A workflow MUST contain **exactly one activity with `isStart: true`** (typically a `route`) and **at least one activity with `isEnd: true`** (typically a `route`).

---

## Output contract

Return **strict JSON only** — no prose, no markdown fences, no commentary. The top-level object must parse as `ProcessDefDto`:

```json
{
  "id": null,
  "name": "<short human-readable name>",
  "version": "1",
  "packageId": null,
  "description": "<one sentence>",
  "variables": [
    { "name": "...", "javaClass": "java.lang.String", "defaultExpression": null, "required": true }
  ],
  "activities": [
    {
      "id": "<unique-string>",
      "name": "<display label>",
      "type": "route|tool|normal|subflow|foreach|while|batch",
      "pluginName": null,
      "properties": {},
      "inputSchema": {},
      "outputSchema": {},
      "deadlineExpression": null,
      "isStart": false,
      "isEnd": false,
      "subflowDefId": null,
      "subflowInputs": {},
      "subflowOutputs": {},
      "outputVariables": [],
      "andJoin": false,
      "errorPolicy": { "retryCount": 0, "backoffMs": 0, "timeoutMs": null, "failFast": false, "continueOnError": false }
    }
  ],
  "transitions": [
    {
      "id": "<unique-string>",
      "fromActivityId": "<activity id>",
      "toActivityId": "<activity id>",
      "condition": null,
      "isErrorEdge": false,
      "matchesErrorClass": null,
      "trigger": "ON_SUCCESS",
      "priority": null,
      "isDefault": false
    }
  ]
}
```

Field rules in one line each:

- `id` (top-level) — leave `null`; engine assigns a UUID.
- `name` — short, generic, reusable (no run-specific values).
- `version` — string `"1"`.
- `packageId` — `null` unless the user explicitly names one.
- `variables[].javaClass` — fully qualified Java class name, e.g. `java.lang.String`, `java.lang.Long`, `java.lang.Boolean`, `java.util.Map`.
- `variables[].defaultExpression` — optional SecureSpel expression; usually `null`.
- `variables[].required` — `true` if the variable must be supplied at run start.
- `activities[].id` — unique non-blank string within the workflow.
- `activities[].pluginName` — required when `type == "tool"`; usually `null` otherwise.
- `activities[].properties` — **MUST be an object map** (`{}`), never an array. Values may be SecureSpel expressions like `"#{#orderId}"`.
- `activities[].inputSchema` / `activities[].outputSchema` — JSON-schema-like contract maps; use `{}` when unconstrained. See the **Schema rules** section below for the exact value the engine validates.
- `activities[].isStart` — exactly one activity has this `true`.
- `activities[].isEnd` — one or more activities have this `true`.
- `activities[].andJoin` — `true` only when this activity must wait for **every** incoming transition. Default `false` (OR-join: fires on first arrival).
- `activities[].outputVariables` — declares variables the plugin's return value writes into.
- `transitions[].id` — unique non-blank string.
- `transitions[].fromActivityId` / `toActivityId` — must reference existing activity ids.
- `transitions[].condition` — SecureSpel expression like `"#status == 'ok'"` or `null` for unconditional.
- `transitions[].isErrorEdge` — `true` for transitions taken only on activity failure.
- `transitions[].matchesErrorClass` — one of `"EXPRESSION"`, `"VALIDATION"`, `"TIMEOUT"`, `"TOOL"`, `"SUBFLOW"`, `"UNCAUGHT"`, or `null` to match any error.
- `transitions[].trigger` — one of `ON_SUCCESS`, `ON_NO_MATCH`, `ON_ERROR`, `ON_TIMEOUT`, `ON_VALIDATION_ERROR`.
- `transitions[].priority` — optional integer; lower value wins when multiple edges match.
- `transitions[].isDefault` — optional fallback edge when no success condition matches.

---

## Schema rules

`inputSchema` and `outputSchema` are JSON-schema-like contracts that the engine evaluates with `WorkflowSchemaValidator`. The most common mistake is emitting a schema whose `type` doesn't match what the engine actually validates. The rules:

**`inputSchema` is validated against the resolved properties map** (the `Map` the engine hands the plugin) — NOT against the inner payload your tool ultimately receives. The keys at the top level of an object-typed `inputSchema` MUST be activity property names (e.g. `toolName`, `input`, `url`, `language`), never tool-payload field names (`id`, `userId`, `query`). Three shapes work:

1. **Empty (recommended default)** — when in doubt, emit `"inputSchema": {}`. No validation, no false negatives. Use this for any tool whose payload shape you're not 100% sure of:

   ```json
   "properties": { "toolName": "getProductById", "input": "#{ {'id': #currentItem.id} }" },
   "inputSchema": {}
   ```

2. **Object shape over plugin properties** — describe the activity property keys (`toolName`, `input`, …), not the inner payload:

   ```json
   "properties": { "toolName": "lookup_order", "input": "#{#orderId}" },
   "inputSchema": {
     "type": "object",
     "required": ["toolName", "input"],
     "properties": {
       "toolName": { "type": "string" },
       "input":    { "type": "string" }
     }
   }
   ```

3. **Single-value shape** — when the plugin takes one canonical `input` property, you may write the schema for that single value:

   ```json
   "properties": { "toolName": "lookup_order", "input": "#{#orderId}" },
   "inputSchema": { "type": "string" }
   ```

   The engine sees the non-object schema and validates against `properties.input` (or the single property if there's only one) instead of the whole map.

**❌ Common mistake — DO NOT do this:**

```json
"properties": { "toolName": "getProductById", "input": "#{ {'id': #currentItem.id} }" },
"inputSchema": {
  "type": "object",
  "required": ["id"],                  ← WRONG. "id" is inside `input`, not at the top.
  "properties": { "id": { "type": "number" } }
}
```

The validator looks for `id` at the top of the properties map and sees only `toolName` and `input` — so this **always** fails with `$.id is required`. If you genuinely want to assert the payload shape, nest it under `input`:

```json
"inputSchema": {
  "type": "object",
  "required": ["toolName", "input"],
  "properties": {
    "toolName": { "type": "string" },
    "input": {
      "type": "object",
      "required": ["id"],
      "properties": { "id": { "type": "number" } }
    }
  }
}
```

When unsure, prefer empty `{}` over a wrong schema — empty fails open, wrong fails closed on every run.

**`outputSchema` is validated against the raw plugin return value by default.** Write the schema for what the tool itself returns:

```json
"outputVariables": [
  { "name": "products", "javaClass": "java.util.List" }
],
"outputSchema": {
  "type": "array",
  "items": { "type": "object" }
}
```

Only when you want to validate the **wrapped** form `{ <outputVarName>: pluginOutput }` should you use an object schema that explicitly mentions the variable name as a property:

```json
"outputVariables": [{ "name": "products", "javaClass": "java.util.List" }],
"outputSchema": {
  "type": "object",
  "required": ["products"],
  "properties": { "products": { "type": "array", "items": { "type": "object" } } }
}
```

**Default to populating schemas.** Empty schemas are accepted but produce a worse review experience and provide no validation safety. For any tool/loop/route activity where you can describe the input or output shape from the tool name and surrounding context, write a real schema using the patterns above. Only fall back to `{}` when the shape is genuinely unknown (e.g. a free-form synthesis tail). **Never** emit a schema whose `type` cannot possibly match what the engine validates (e.g. `"type": "boolean"` on an output variable that holds a `Map`). It will fail every run with a `VALIDATION` error.

For loop activities (`foreach`/`while`/`batch`) the schemas describe the loop's *bookkeeping output* (`{continue, index, size}`) — keep them as `{}` unless you have a strong reason.

`CodeExecPlugin` returns `{ "success": bool, "output": …, "stdout": str, "stderr": str }`; the entire wrapper lands in your output variable, so reference `#yourVar.output` downstream — not `#yourVar` directly — when you only want the script's return value.

---

## CRITICAL — Replay the turn's agent tools, don't reinvent them

The workflow proposal is generated from a chat turn that already executed one or more **agent tools** (the prompt lists them under "Tool names used in turn"). Those tools are the whole reason a workflow is being proposed. You MUST replay them, not substitute them.

For every tool name in that list, emit a `tool` activity shaped like:

```json
{
  "id": "<unique>",
  "name": "<short label>",
  "type": "tool",
  "pluginName": "AgentToolPlugin",
  "properties": { "toolName": "<exact name from the list>", "input": "#{#someVariable}" },
  "outputVariables": [
    { "name": "<varName>", "javaClass": "java.util.Map", "defaultExpression": null, "required": false }
  ]
}
```

**Never** replace an agent-tool step with `HttpRequestPlugin` and a URL you guessed at — even if you know what the tool does internally. The runtime resolves `toolName` against `ToolRegistryService`, including auth, retries, and audit hooks; raw HTTP loses all of that.

`HttpRequestPlugin`, `McpToolPlugin`, `CodeExecPlugin`, and `AiChatPlugin` are reserved for steps that are **not** on the tool-names list — typically an optional synthesis tail that the original turn used the assistant for.

### CRITICAL — Do not invent activities the turn didn't execute

The workflow must mirror the chat turn's actual tool calls, in order. **Do not add "setup", "init", "load", "validate", "prepare", "warmup", or "skill-loading" steps that the turn never executed.** These ghost activities waste runtime, add new failure points, and frequently call tools that don't exist or that the architect made up.

The architect commonly hallucinates one of these patterns — **all are forbidden**:

| Ghost step the architect tries to add | Why it's wrong |
|---|---|
| `loadSkill` / `loadConfig` / `initSession` calling `AgentToolPlugin` with `toolName: "skill"` (or similar non-existent name) | The turn didn't load a skill at runtime — skills are baked into the chat agent's prompt, not invoked as workflow tools. There is no agent tool called `skill`. |
| `Load Workflow Architect` / `Load Order Validation Skill` / any `Load <Something> Skill` | Skills are not loadable at runtime. They live in the chat-agent's prompt only. There is no `loadSkill` tool, no `skill` tool, no `workflow-architect` tool. Emit zero of these. |
| `validateInput` / `parseRequest` / `normalizeParams` as a `CodeExecPlugin` step | Validation/normalization belongs inside the tool itself or as a transition `condition`. Not a separate node. |
| `logResult` / `auditOutcome` / `trackMetric` at the end | The engine writes audit rows automatically. Don't reimplement it. |
| A second `fetchList` or `lookupUser` "to be safe" before the real one | One call per turn-step. No defensive double-fetches. |
| `Authenticate` / `Get Token` / `Refresh Session` before a tool call | Auth is handled inside the tool plugin by `ToolRegistryService`. The workflow never sees credentials. |

#### Activity-name prefix blacklist

If an activity you're about to emit has a `name` (or `id`) that matches any of these case-insensitive prefixes, treat it as a probable ghost and apply the mechanical filter from the top-of-file prohibitions:

```
Load …, Init …, Initialize …, Setup …, Prepare …, Bootstrap …, Warmup …,
Validate Input, Normalize …, Parse Request, Authenticate, Get Token,
Refresh Session, Log Result, Audit Outcome, Track Metric, Finalize,
Cleanup, Teardown, Notify, Emit Event
```

The activity survives only if its `toolName` is literally present in `summary.toolNames` from the execution log. Otherwise: delete.

**Pre-emit checklist — run this for every activity you are about to write:**

1. Is its `pluginName` `AgentToolPlugin`? If yes, is `properties.toolName` literally present in `summary.toolNames`? If no → **delete**.
2. Does its `name` match the prefix blacklist above? If yes, did step 1 already validate the tool exists? If no → **delete**.
3. Does the chat turn's `userPrompt` or an `architect_note` explicitly request this step? If neither, and step 1/2 didn't justify it → **delete**.

**Rule**: the count of `AgentToolPlugin` activities you emit MUST equal the count of distinct agent-tool *call-sites* in the turn (one per loop body, even if the turn called it many times). If a tool name appears in your activity list that wasn't in the prompt's tool-names list, delete that activity.

**When in doubt: emit fewer activities.** A workflow with `start → fetchList → end` is better than `start → loadSkill → setup → fetchList → log → end`. The four extra nodes add failure surface, are visible in the UI as broken-looking dead weight, and will get the workflow proposal rejected.

Bad — workflow generated from a turn that only called `getAllProducts` and `getProductById`:
```
start → loadSkill (toolName: "skill")   ← invented, no such tool
      → fetchList (toolName: "getAllProducts")
      → iterate → fetchDetail (toolName: "getProductById") → accumulate
      → end
```

Good — same turn:
```
start → fetchList (toolName: "getAllProducts")
      → iterate → fetchDetail (toolName: "getProductById") → accumulate
      → end
```

If the user's intent genuinely requires a step that's *not* in the turn (e.g., they explicitly said "after that, save to S3"), you may add it — but only when that intent is in the turn's `userPrompt` or an `architect_note`. Never on speculation.

## Good vs bad mini examples

### 1) Transition-only branching (good)

```json
{
  "transitions": [
    { "id": "ok", "fromActivityId": "check", "toActivityId": "next", "condition": "#approved == true", "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 10, "isDefault": false },
    { "id": "fallback", "fromActivityId": "check", "toActivityId": "review", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_NO_MATCH", "priority": 100, "isDefault": true }
  ]
}
```

Bad: `route` activity with decision plugin logic or missing no-match/default path.

### 2) Loop semantics (good)

A loop activity (`foreach`/`while`/`batch`) needs **two outgoing edges and one back-edge from the body**. The engine writes `__loop_continue_<activityId>` into the variable scope on every dispatch; when it's `false` the engine automatically prefers the no-match/default edge over the unconditional success edge so the loop exits cleanly.

```json
{
  "activities": [
    {
      "id": "iterate",
      "type": "foreach",
      "pluginName": null,
      "properties": {
        "collection": "#{#items}",
        "itemVar":    "currentItem",
        "indexVar":   "currentIndex",
        "maxIterations": 1000
      },
      "outputVariables": []
    }
  ],
  "transitions": [
    { "id": "t-loop-body",
      "fromActivityId": "iterate", "toActivityId": "handleItem",
      "condition": null, "isErrorEdge": false, "matchesErrorClass": null,
      "trigger": "ON_SUCCESS",  "priority": null, "isDefault": false },

    { "id": "t-loop-exit",
      "fromActivityId": "iterate", "toActivityId": "end",
      "condition": null, "isErrorEdge": false, "matchesErrorClass": null,
      "trigger": "ON_NO_MATCH", "priority": 100,  "isDefault": true },

    { "id": "t-body-back",
      "fromActivityId": "handleItem", "toActivityId": "iterate",
      "condition": null, "isErrorEdge": false, "matchesErrorClass": null,
      "trigger": "ON_SUCCESS",  "priority": null, "isDefault": false }
  ]
}
```

Required:

- One `ON_SUCCESS` edge (no condition) from the loop activity to the **body** of the loop. It will be skipped automatically once the loop exhausts.
- One `ON_NO_MATCH` (or `isDefault: true`) edge from the loop activity to the **exit/next step**. This fires exactly once, when the iteration finishes.
- One `ON_SUCCESS` back-edge from the last activity in the body to the loop activity itself.
- `properties.maxIterations` MUST be a positive integer (engine validation).
- `properties.itemVar`/`indexVar` (foreach), `properties.condition` (while), `properties.batchSize`/`batchVar` (batch) — set the names you'll reference downstream.

Optional but recommended: an explicit guard like `"#__loop_continue_iterate == true"` on the body edge if you want to be defensive. The engine handles the natural pattern above without it.

Bad: list-processing flow built with repeated route hops and no max-iteration guard. Bad: a single edge out of the loop activity (the loop will never exit). Bad: making the body back-edge an error edge.

For a complete fan-out + accumulate template see [templates/foreach-accumulate.json](templates/foreach-accumulate.json).

### 3) AI_REASONING nodes — the only LLM call the runtime is allowed to make

The deterministic runtime is barred from calling the LLM through any other node type. If a step in the source turn was genuinely judgement-driven (the chat agent itself reasoned about something — classification, summarization, fraud verdict, deduping, ranking, intent extraction), promote that step to an `ai_reasoning` activity. **Don't reach for `AiChatPlugin` as a tool** — that path is reserved for steps the source turn explicitly executed via the chat assistant, and doesn't carry the planning-vs-runtime separation the architecture depends on.

Required `properties`:

| Property     | Required | Notes |
|--------------|----------|-------|
| `prompt`     | yes      | User prompt. SecureSpel expressions resolved at dispatch (`#{...}`). |
| `system`     | no       | System prompt. |
| `invokeWhen` | no       | SecureSpel boolean. When present **and false**, the node skips the LLM call and writes `{skipped: true, reason: "invokeWhen=false"}`. Absent ⇒ always run. |
| `providerID` / `modelID` | no | Per-node model override. Falls back to run-scope `__providerID` / `__modelID`. Use this when the architect knows a smaller / cheaper model is enough for this step. |
| `model`      | no       | Shorthand "provider/modelID" string; equivalent to setting both above. |

Output (always written to the first declared output variable):

```json
{
  "text": "<assistant message>",
  "finishReason": "stop|length|...",
  "usage": { "promptTokens": 123, "completionTokens": 45, "totalTokens": 168 },
  "model": { "providerID": "anthropic", "modelID": "claude-sonnet-4-7" },
  "skipped": false
}
```

Downstream activities reference **`#yourVar.text`** — never `#yourVar` directly — when they want the assistant message.

Good example (loop products, classify each one with a small model only when total > 500):

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
  ],
  "inputSchema":  {},
  "outputSchema": {
    "type": "object",
    "required": ["text"],
    "properties": {
      "text":         { "type": "string" },
      "skipped":      { "type": "boolean" },
      "finishReason": { "type": "string" }
    }
  },
  "isStart": false, "isEnd": false
}
```

Bad:
- Using `AiChatPlugin` as a `tool` activity for a judgement step. Use `ai_reasoning` instead.
- Multiple `ai_reasoning` nodes that all do the same classification — collapse them, parameterize the prompt.
- Putting `ai_reasoning` nodes inside loops without `invokeWhen` to keep the LLM bill bounded.
- Forgetting that `outputVariables[0]` receives a `Map`, not a string. Downstream uses `#yourVar.text`.

When **not** to emit `ai_reasoning`:
- The source turn called a registered tool (an MCP, an OpenAPI binding, etc.) — that's a `tool` activity, regardless of how "AI-ish" the tool's name sounds.
- The decision is a pure conditional — use a `route` activity with a transition `condition` instead.
- The transformation is deterministic (rename a field, sum numbers) — use `CodeExecPlugin` or SecureSpel.

For a complete fan-out + classify-per-item template see [templates/foreach-ai-reasoning.json](templates/foreach-ai-reasoning.json).

### 4) Error policy + error transition (good)

```json
{
  "activity": {
    "id": "call_tool",
    "type": "tool",
    "errorPolicy": { "retryCount": 2, "backoffMs": 250, "timeoutMs": 5000, "failFast": false, "continueOnError": false }
  },
  "transition": {
    "id": "on_tool_error",
    "fromActivityId": "call_tool",
    "toActivityId": "recover",
    "condition": null,
    "isErrorEdge": true,
    "matchesErrorClass": "TOOL",
    "trigger": "ON_ERROR",
    "priority": 1,
    "isDefault": false
  }
}
```

Bad: retries/timeouts implied in prose only, with no `errorPolicy` or error-edge transition in JSON.

## Every `#name` must be declared

If any property value or transition condition contains `#foo`, then `variables[]` MUST contain an entry with `"name": "foo"`. This includes booleans you use as branch guards (`#fetchAll`, `#doSynthesize`) and inputs you pass into tools (`#orderId`, `#apiBase`). Give them a sensible `defaultExpression` so the workflow can run with no inputs:

```json
"variables": [
  { "name": "fetchAll",     "javaClass": "java.lang.Boolean", "defaultExpression": "false", "required": false },
  { "name": "doSynthesize", "javaClass": "java.lang.Boolean", "defaultExpression": "false", "required": false }
]
```

Empty `variables: []` is only valid if no `#name` is referenced anywhere in the workflow.

## Available plugins (pluginName values for `type: "tool"`)

| Plugin              | Required props                | Optional props                                  | What it does                                                          |
|---------------------|-------------------------------|--------------------------------------------------|-----------------------------------------------------------------------|
| `AgentToolPlugin`   | `toolName`                    | `input` (string or map)                          | Invokes a registered agent tool by name.                              |
| `SkillToolPlugin`   | `name`                        | —                                                | Loads a skill bundle by name; returns `{name,description,skillContent,files}`. |
| `HttpRequestPlugin` | `url`                         | `method`, `headers`, `body`, `timeoutMs`         | Performs an HTTP call; non-2xx is returned not thrown.                |
| `McpToolPlugin`     | `serverId`, `toolName`        | `payload` (JSON string)                          | Invokes a tool on a registered MCP server.                            |
| `CodeExecPlugin`    | `language`, `code`            | `input` (map), `timeoutMs`, `memoryLimitMb`      | Runs sandboxed code. **Always use `language: "java"`** — other languages are temporarily disabled. The runtime wraps your snippet as the body of `public static Object run(Map<String,Object> input) throws Exception { … }`; use fully-qualified type names (`java.util.List`, `java.util.ArrayList`, `java.util.LinkedHashMap`) since only `java.util.Map` is auto-imported. Blocked APIs: `java.io`, `java.net`, `java.nio.file`, `ProcessBuilder`, `Runtime`, `java.lang.reflect`, `ClassLoader`, `Thread`. |
| `AiChatPlugin`      | `provider`, `model`, `prompt` | `system`                                         | Calls an LLM and returns the text reply.                              |

See [references/plugins.md](references/plugins.md) for full property docs and return shapes.

---

## Expression binding — SecureSpel

Property values formatted **`#{...}`** are evaluated as SecureSpel expressions when the activity dispatches. Bare strings are passed through literally.

- Variable read inside `#{}`: `#orderId` (note the leading `#`).
- Examples in `properties`:
  - `"toolName": "validate_order"` — literal string.
  - `"input": "#{#orderId}"` — value of variable `orderId`.
  - `"url": "#{'https://api/' + #region + '/orders'}"` — expression.
- Examples in transition `condition`:
  - `"#approved == true"`
  - `"#status != 'rejected'"`
  - `"#count > 0"`

The sandbox blocks `T(...)`, `new Class(...)`, `@bean`, `getClass`, classloader access. Stick to variable reads, arithmetic, comparisons, and string concatenation.

See [references/variables-and-expressions.md](references/variables-and-expressions.md).

---

## Routing semantics (transitions)

On activity success:
1. The engine takes every outgoing **non-error** transition whose `condition` is null or evaluates to true.
2. If multiple match → AND-split: every target activity is enqueued.
3. If none match and the activity isn't an end node → workflow stalls.

On activity failure:
1. The engine takes only **error edges** (`isErrorEdge: true`).
2. If `matchesErrorClass` is set, only the matching error class is taken.
3. The error class is one of `EXPRESSION`, `VALIDATION`, `TIMEOUT`, `TOOL`, `SUBFLOW`, `UNCAUGHT`.

Joins:
- `andJoin: false` (default) — activity fires on the first incoming arrival; later arrivals are ignored.
- `andJoin: true` — activity waits for **every** incoming transition that *could* fire before running. Use for parallel-gateway joins.

See [references/transition-routing.md](references/transition-routing.md).

---

## End-activity result — what the run returns over the API

Every `end` activity SHOULD set `properties.result` to a SecureSpel expression that captures the workflow's natural output. The engine evaluates the expression against the run's final variable scope on `PROCESS_COMPLETED` and surfaces the JSON-encoded value as the `result` field of the run-summary API response (`POST /api/v1/workflow/runs` and `GET /api/v1/workflow/runs/{id}`).

This is what makes a workflow **callable** from outside the app — without it, external callers have to make a second round-trip to `GET /runs/{id}/activities` and dig the answer out of the last activity's `outputSnapshot`. With it, the answer comes back in the same response that started the run.

```json
{
  "id": "end",
  "name": "End",
  "type": "route",
  "isEnd": true,
  "properties": { "result": "#{#details?.output}" },
  "inputSchema": {},
  "outputSchema": {}
}
```

**Expression by pattern**:

| Workflow shape | Suggested `result` expression |
|---|---|
| Accumulator loop (`foreach` + `CodeExecPlugin`) | `"#{#details?.output}"` — the final array of accumulated items |
| Single-tool lookup (`AgentToolPlugin` returning a Map) | `"#{#orderDetail}"` — the tool's parsed body |
| LLM synthesis (`ai_reasoning`) | `"#{#answer?.text}"` — just the assistant message |
| Numeric reduction (`CodeExecPlugin` summing) | `"#{#execResult?.output}"` — the script's return value |
| Workflow with no meaningful output | omit `properties.result` entirely; API response will simply lack a `result` field |

**Rules**:
- The expression is best-effort. Eval failures (missing variable, type mismatch) are audited as `expression.failed` but **do not fail the run** — the workflow has already succeeded.
- The value is serialized with Jackson; anything that survives `objectMapper.writeValueAsString(...)` is fair game (Maps, Lists, primitives, null).
- Multiple end activities: each may have its own `result`; only the one actually reached is evaluated.
- The result is computed at terminal time, against the final scope — `#someVar` reads the value that variable had when the workflow ended.

---

## Hard rules (these will fail validation)

- ✅ JSON parses directly as `ProcessDefDto`. No markdown fences in your output.
- ✅ Every activity `type` is one of `normal`, `tool`, `route`, `subflow`, `foreach`, `while`, `batch`, `ai_reasoning`.
- ✅ Exactly one activity has `isStart: true`.
- ✅ All activity `id`s are unique.
- ✅ Every transition's `fromActivityId` and `toActivityId` references an existing activity.
- ✅ `route` activities do not use decision plugins; branching is encoded only on transitions.
- ✅ `ai_reasoning` activities have `properties.prompt` set, at least one `outputVariables` entry, and **no** `pluginName`.
- ✅ `properties` is an object map (`{}`), never an array.
- ✅ Branching transitions include explicit `trigger`; no-match/default path exists where needed.
- ✅ No run-specific literals — UUIDs, 4+-digit numbers, order numbers, customer names from the source turn. Use variables.
- ✅ Use the canonical plugin names listed above. Don't invent plugins.
- ✅ **`defaultExpression` for `java.util.Map` variables MUST be `null`** (preferred) or the SpEL empty-map literal `"{:}"`. **Never** `"{}"` — SpEL parses that as an empty `List` and the workflow will dead-end on the very first activity. See [references/variables-and-expressions.md](references/variables-and-expressions.md#common-spel-pitfalls-read-this-before-writing-defaultexpression).
- ✅ `inputSchema` `required` arrays must list ONLY the activity properties the underlying tool actually requires. Tools that take no input (e.g. list-all endpoints) get an empty `inputSchema: {}` — do NOT mark `input` as required for those.
- ✅ **Every `CodeExecPlugin` activity MUST set `"language": "java"`.** Python / JavaScript / TypeScript are temporarily disabled — emit Java only. Inside the snippet, use fully-qualified type names (`java.util.List`, `java.util.ArrayList`, `java.util.LinkedHashMap`) and `return` the value; do NOT assign to a magic variable. Avoid blocked APIs (`java.io`, `java.net`, `java.nio.file`, `ProcessBuilder`, `Runtime`, reflection, `ClassLoader`, `Thread`).
- ✅ **No invented activities.** Every `AgentToolPlugin` activity's `toolName` MUST appear in the turn's "Tool names used in turn" list. No `loadSkill`/`initSession`/`validateInput`/`logResult` ghosts. If a tool name in your activities isn't in that list, delete the activity.
- ✅ **Every `end` activity SHOULD set `properties.result`** to a SecureSpel expression that captures the workflow's natural output (e.g. `"#{#details?.output}"` for accumulator patterns, `"#{#orderDetail}"` for single-lookup patterns, `"#{#answer?.text}"` for AI synthesis). Omit only when the workflow has no meaningful return value.

---

## Patterns and templates

For common shapes, copy and adapt one of the templates below.

| User intent                                | Template file                                                    |
|--------------------------------------------|-------------------------------------------------------------------|
| Simple linear: start → tool → end          | [templates/basic-linear.json](templates/basic-linear.json)        |
| HTTP call with error recovery              | [templates/http-with-error-route.json](templates/http-with-error-route.json) |
| If/else decision (XOR)                     | [templates/xor-decision.json](templates/xor-decision.json)        |
| Parallel tools joined (AND-split / join)   | [templates/and-split-join.json](templates/and-split-join.json)    |
| Tools then LLM synthesis                   | [templates/llm-synthesis.json](templates/llm-synthesis.json)      |
| Call an MCP-server tool                    | [templates/mcp-tool-call.json](templates/mcp-tool-call.json)      |
| Run sandboxed code                         | [templates/code-exec.json](templates/code-exec.json)              |
| Fetch list, then detail-per-item, accumulate | [templates/foreach-accumulate.json](templates/foreach-accumulate.json) |
| Fetch list, classify each item with AI (gated)   | [templates/foreach-ai-reasoning.json](templates/foreach-ai-reasoning.json) |

See [references/workflow-patterns.md](references/workflow-patterns.md) for the full pattern catalog and the decision matrix.

---

## Quality checklist (run before returning)

0. **Ghost-step sweep** — for every activity in your draft:
   - If `pluginName == "AgentToolPlugin"`, confirm `properties.toolName` is a literal member of `summary.toolNames` from the execution log. If not → delete the activity AND its incident transitions, then rewire.
   - If the activity `name` starts with `Load `, `Init`, `Setup`, `Prepare`, `Bootstrap`, `Warmup`, `Validate Input`, `Normalize`, `Authenticate`, `Get Token`, `Log Result`, `Audit `, `Track`, `Finalize`, `Cleanup`, `Teardown`, `Notify`, `Emit Event` (case-insensitive) — and step above didn't already validate it — delete it.
   - The count of `AgentToolPlugin` activities you emit must equal the count of distinct agent-tool call-sites in the turn. Recount.
0a. **Input-shape fidelity sweep** — for every `AgentToolPlugin` activity:
   - Look up the matching `step` in `execution-log.steps` where `step.tool == this.toolName`.
   - Count the top-level keys of `step.input`. Call that N.
   - Your `properties.input` SecureSpel literal must have the same N top-level keys with the same names. If `step.input` was `{tableName, inputs}`, your literal must be `#{ {'tableName': ..., 'inputs': ...} }` — NOT `#{#order}` or any other single-var collapse.
   - Replace run-specific values (UUIDs, ids, names) with variable references; preserve literal strings only when they're identifiers like `tableName` values that parameterize WHICH operation runs.
1. JSON parses as `ProcessDefDto`. No prose, no fences, no trailing commas.
2. Every `type` ∈ {`normal`, `tool`, `route`, `subflow`, `foreach`, `while`, `batch`, `ai_reasoning`}.
3. Exactly one `isStart: true`.
4. At least one `isEnd: true`.
5. All activity `id`s unique. All transition endpoints valid.
6. All `properties` are objects (not arrays, not strings).
7. Every `tool` activity has a `pluginName` from the canonical list.
8. Every `ai_reasoning` activity has `properties.prompt`, at least one `outputVariables` entry, and no `pluginName`.
9. No `AiChatPlugin` tool activities for judgement steps — those become `ai_reasoning` nodes instead.
10. No UUIDs, order numbers, or literals from the source turn — use variables.
11. Transitions form a connected path from start → … → end.
12. Variables for every runtime input are declared with `name`, `javaClass`, `required`.
13. Every transition has a valid trigger (`ON_SUCCESS`, `ON_NO_MATCH`, `ON_ERROR`, `ON_TIMEOUT`, `ON_VALIDATION_ERROR`).
14. List-processing flows use `foreach`/`while`/`batch` plus max-iteration guardrails.
15. No `defaultExpression: "{}"` on `java.util.Map` variables — that's an empty SpEL `List`. Use `null`.
16. No `inputSchema.required` field that isn't a real prerequisite of the underlying tool.
17. Every `AgentToolPlugin` `toolName` matches one in the turn's "Tool names used in turn" list. No ghost steps (`loadSkill`, `initSession`, `validateInput`, `logResult`, etc.).
18. Every `inputSchema` either is `{}` or describes the **activity property keys** (`toolName`, `input`, …) — never the inner tool payload keys (`id`, `userId`, …) at the top level.
19. Every `end` activity has `properties.result` set to a SecureSpel expression capturing the workflow's natural output — unless the workflow genuinely has no meaningful return value.

---

## For the chat agent: when to drop architect notes

The chat agent has access to a single write-scoped tool relevant to this skill:

```
architect_note(note: string)
```

Each call appends one `architect_note` step into the in-flight execution log. The Workflow Architect reads those notes after the turn completes and uses them to decide the workflow shape. **You do not need to write JSON, structure, or anything formal** — just short, concrete annotations that mark logical boundaries the architect would otherwise have to guess from raw tool patterns.

### When to call `architect_note`

Call it **before** a logical block, not after, and keep each note to a single sentence:

| Situation | Good note |
|-----------|-----------|
| You're about to call the same tool many times with varying inputs | `"loop products: for each item in #products call get_product"` |
| You're branching on a value | `"condition: only call review_tool when total > 500"` |
| You'll fan out to multiple tools in parallel | `"parallel fan-out: inventory + pricing + tax in parallel"` |
| You're about to call the LLM yourself for a judgement-style decision | `"ai_reasoning here: classify fraud risk from cart contents"` |
| You're handling a tool failure and want to reuse the recovery logic | `"on tool error: fall back to cached price from #lastQuote"` |
| You used a tool result as input to the next tool | `"chain: orderId from get_orders feeds get_order_detail"` |

### When NOT to call `architect_note`

- Don't narrate every tool call — the architect already sees them.
- Don't write the workflow JSON yourself — that's the architect's job.
- Don't use it for debugging logs or reasoning chains — those go in your normal response.
- Don't repeat yourself; one note per logical boundary is plenty.

### Cost / scope

- Notes are append-only and scoped to the current turn.
- They cost one DB row each; aim for **2–5 notes per turn**, not dozens.
- Notes are visible to the user in the UI (rendered as a tool-call chip), so write them as if a human is reading: short, plain, useful.

### Example — a turn that fetches every product

The chat agent's tool-call sequence:

1. `architect_note("loop products: get_all_products → foreach product, call get_product_by_id, accumulate")`
2. `get_all_products()`
3. `get_product_by_id(id=1)`
4. `get_product_by_id(id=2)`
5. … (more)

That single note is enough for the architect to emit a `foreach`-with-accumulate workflow instead of trying to infer it from 20 nearly-identical tool calls.
