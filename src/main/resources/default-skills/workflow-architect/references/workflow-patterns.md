# Workflow patterns

A decision matrix of common shapes and which template to start from. Copy the JSON, then rename ids, swap plugins, and parameterize literals into `variables`.

| User intent                                              | Start template                                                       |
|----------------------------------------------------------|-----------------------------------------------------------------------|
| Single tool call, linear path                            | [templates/basic-linear.json](../templates/basic-linear.json)         |
| HTTP call with error fallback                            | [templates/http-with-error-route.json](../templates/http-with-error-route.json) |
| Branching on a boolean / status field                    | [templates/xor-decision.json](../templates/xor-decision.json)         |
| Two or more tools running in parallel, results joined    | [templates/and-split-join.json](../templates/and-split-join.json)     |
| Tools followed by an LLM synthesis tail                  | [templates/llm-synthesis.json](../templates/llm-synthesis.json)       |
| Call an MCP-server tool                                  | [templates/mcp-tool-call.json](../templates/mcp-tool-call.json)       |
| Run sandboxed code (Python / JS / Java / TS)             | [templates/code-exec.json](../templates/code-exec.json)               |
| Fetch a list, then detail-per-item, accumulate results   | [templates/foreach-accumulate.json](../templates/foreach-accumulate.json) |
| Fetch a list, classify each item with AI (gated), accumulate verdicts | [templates/foreach-ai-reasoning.json](../templates/foreach-ai-reasoning.json) |

## How to adapt a template

1. **Rename activity ids** to something readable (`start`, `fetchOrder`, `decide`, `end`). Keep them unique.
2. **Replace the `pluginName`** with the right plugin from [plugins.md](plugins.md).
3. **Move literals into `variables`.** A user prompt like *"validate order 4567"* becomes `variables: [{ "name": "orderId", "javaClass": "java.lang.String", "required": true }]` and `properties: { "input": "#{#orderId}" }`.
4. **Wire transitions.** Every activity must be reachable from the start and able to reach an `isEnd: true`.
5. **Add error edges** for any `tool` step that talks to the network.

## Choosing the right shape

- One tool, no branching → linear template.
- Decision based on tool output → XOR template; the route activity follows the tool, conditions on its `outputVariables`.
- Independent work that should run in parallel → AND-split / AND-join template; the join sets `andJoin: true`.
- "Fetch from N sources, summarize" → AND-split + LLM synthesis; combine the parallel and synthesis templates.
- "Fetch a list, then per-item call another tool, then accumulate" → foreach-accumulate template. Use a `foreach` activity with `properties.collection`, `itemVar`, `indexVar`, `maxIterations`. The body runs per item; a back-edge from the last body activity returns to the loop activity. The exit edge is `ON_NO_MATCH`/`isDefault: true` and fires once when the iteration is exhausted.
- "Process items in chunks of N" → batch template (`type: "batch"`, `properties.batchSize`).
- "Repeat while condition holds" → while loop (`type: "while"`, `properties.condition`).
- Anything BPMN-shaped (`startEvent`, gateways, etc.) → re-express as the four canonical types before producing JSON.

## Loop transitions — the only correct shape

Loop activities (`foreach`/`while`/`batch`) need exactly three edges to behave correctly:

1. `loopActivity` --`ON_SUCCESS`, no condition--> `bodyEntry`
2. `loopActivity` --`ON_NO_MATCH` (or `isDefault: true`)--> `loopExit`
3. `bodyExit` --`ON_SUCCESS`--> `loopActivity` (back-edge)

The engine writes `__loop_continue_<loopActivityId>` into the variable scope each dispatch. When the loop is exhausted the engine **automatically** suppresses the unconditional success edge so the no-match/default edge wins — you do not need to add the guard yourself, but you may add `condition: "#__loop_continue_<id> == true"` on edge (1) for clarity.

Do **not** route the body-error case back through the loop on an `ON_ERROR` edge unless `errorPolicy.continueOnError: true` is also set on the body — otherwise the error short-circuits the run before the back-edge can fire.

## Common mistakes to avoid

- ❌ `"type": "startEvent"` — use `"type": "route", "isStart": true`.
- ❌ `"type": "endEvent"` — use `"type": "route", "isEnd": true`.
- ❌ `"type": "task"` or `"serviceTask"` — use `"type": "tool"` with a `pluginName`.
- ❌ `"properties": []` — must be an object map, never an array.
- ❌ Hardcoding `"orderId": "12345"` — declare a variable and use `"#{#orderId}"`.
- ❌ Two activities with `"isStart": true` — only one is permitted.
- ❌ Forgetting `pluginName` on a `tool` activity — required.
- ❌ Inventing a plugin like `"DatabaseQueryPlugin"` — use only the names listed in [plugins.md](plugins.md).
