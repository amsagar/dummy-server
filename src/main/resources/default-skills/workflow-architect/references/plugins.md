# Plugin catalog

Use these `pluginName` values for `type: "tool"` activities. The class names are exact — they're matched as Spring bean names.

---

## `AgentToolPlugin`

Invokes a registered agent tool by name (the same set the chat assistant uses).

| Property   | Type           | Required | Notes                                                                |
|------------|----------------|----------|----------------------------------------------------------------------|
| `toolName` | string         | yes      | Name of an enabled tool in `ToolRegistryService`.                    |
| `input`    | string \| map  | no       | Defaults to `{}`. Accepts a JSON string or an object.                |

**Returns:** the tool's output. JSON object/array bodies are parsed (so downstream references like `#order.total` or `#items[0]` work directly); non-JSON bodies are returned as a string. Throws on unknown/disabled tool or execution failure.

```json
{
  "id": "callTool",
  "name": "Call Validate Order",
  "type": "tool",
  "pluginName": "AgentToolPlugin",
  "properties": { "toolName": "validate_order", "input": "#{#orderId}" }
}
```

---

## `SkillToolPlugin`

Loads a skill bundle (Markdown + companion files) by name. Useful when you want a downstream activity (e.g. `AiChatPlugin`) to read skill content as context.

| Property | Type   | Required | Notes                            |
|----------|--------|----------|----------------------------------|
| `name`   | string | yes      | Exact skill name (case-insensitive). |

**Returns:** `{ "name", "description", "skillContent", "files" }`.

---

## `HttpRequestPlugin`

Performs an HTTP request. Non-2xx responses are returned (not thrown); only connection-level failures throw.

| Property    | Type           | Required | Default      | Notes                                          |
|-------------|----------------|----------|--------------|------------------------------------------------|
| `url`       | string         | yes      | —            | Full URL. May be a `#{...}` expression.        |
| `method`    | string         | no       | `"GET"`      | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`.       |
| `headers`   | map<string,string> | no   | `{}`         | Header names → values.                         |
| `body`      | string \| map  | no       | none         | Maps are sent as JSON.                         |
| `timeoutMs` | number         | no       | `30000`      | Per-request timeout.                           |

**Returns:** `{ "status": int, "headers": map, "body": string }`.

```json
{
  "id": "fetchUser",
  "name": "Fetch user",
  "type": "tool",
  "pluginName": "HttpRequestPlugin",
  "properties": {
    "method": "GET",
    "url":    "#{'https://api.example.com/users/' + #userId}",
    "headers": { "Accept": "application/json" }
  }
}
```

---

## `McpToolPlugin`

Calls a tool exposed by a registered MCP server.

| Property   | Type   | Required | Notes                                                |
|------------|--------|----------|------------------------------------------------------|
| `serverId` | string | yes      | Id of an active MCP server.                          |
| `toolName` | string | yes      | Tool exposed by that server.                         |
| `payload`  | string | no       | JSON string passed verbatim. Defaults to `"{}"`.     |

**Returns:** raw string from the MCP server.

---

## `CodeExecPlugin`

Runs sandboxed code. Output is the function's return value; stdout/stderr captured separately.

| Property        | Type       | Required | Notes                                                       |
|-----------------|------------|----------|-------------------------------------------------------------|
| `language`      | string     | yes      | **Must be `"java"`** — other languages (`javascript`, `python`, `typescript`) are temporarily disabled. |
| `code`          | string     | yes      | Source. The runtime supplies `input` as a variable.         |
| `input`         | map        | no       | Bindings exposed to the sandbox.                            |
| `timeoutMs`     | number     | no       | Wall-clock cap.                                             |
| `memoryLimitMb` | number     | no       | Heap cap.                                                   |

**Returns:** `{ "success": boolean, "output": ..., "stdout": string, "stderr": string }`. On failure throws — the engine routes via the error edge.

---

## `AiChatPlugin`

Calls an LLM via `ModelProviderRouter` and returns the assistant text.

| Property   | Type   | Required | Notes                                            |
|------------|--------|----------|--------------------------------------------------|
| `provider` | string | yes      | e.g. `anthropic`, `openai`, `azure_openai`.      |
| `model`    | string | yes      | Provider-specific model id.                      |
| `prompt`   | string | yes      | User message; supports `#{...}` expressions.     |
| `system`   | string | no       | System prompt.                                   |

**Returns:** the assistant reply as a string.

```json
{
  "id": "synthesize",
  "name": "Synthesize answer",
  "type": "tool",
  "pluginName": "AiChatPlugin",
  "properties": {
    "provider": "anthropic",
    "model":    "claude-sonnet-4-6",
    "system":   "Summarize the upstream data in two sentences.",
    "prompt":   "#{#searchResults}"
  },
  "outputVariables": [{ "name": "summary", "javaClass": "java.lang.String", "defaultExpression": null, "required": false }]
}
```

---

## Reserved properties

`outputVariables` on the activity (not on `properties`) declares where the plugin's return value is written. The first declared variable receives the plugin's full return; map fields are accessible downstream via `#varName.fieldName` SpEL syntax.
