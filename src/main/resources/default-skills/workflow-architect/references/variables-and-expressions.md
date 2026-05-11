# Variables and expressions

## Declaring variables

Every runtime input belongs in the top-level `variables` array as a `VariableSpecDto`:

```json
{
  "name":              "<identifier>",
  "javaClass":         "<fully qualified Java class>",
  "defaultExpression": null,
  "required":          true
}
```

| Field               | Notes                                                                                       |
|---------------------|---------------------------------------------------------------------------------------------|
| `name`              | Non-blank identifier. Reference it as `#name` inside expressions.                           |
| `javaClass`         | Type hint matching Joget's `WorkflowVariable.getJavaClass()`. Common values below.          |
| `defaultExpression` | Optional SecureSpel expression evaluated at process start. `null` leaves the variable unset until written. |
| `required`          | If `true`, the run fails fast when the variable isn't supplied or computed.                 |

### Common `javaClass` values

| Use                  | `javaClass`                |
|----------------------|----------------------------|
| Text                 | `java.lang.String`         |
| Whole number         | `java.lang.Long`           |
| Decimal              | `java.lang.Double`         |
| Boolean              | `java.lang.Boolean`        |
| Object / JSON map    | `java.util.Map`            |
| List                 | `java.util.List`           |

## Expression syntax — `#{...}`

Property values written as `#{...}` are evaluated by `SecureSpelEvaluator` against the current variable scope. Anything else is treated as a literal string. Transition `condition` strings are evaluated as raw SpEL (no wrapping `#{}`).

| Form                                | Meaning                                                |
|-------------------------------------|--------------------------------------------------------|
| `#{#orderId}`                       | Read variable `orderId`.                               |
| `#{#user.email}`                    | Read field `email` of map variable `user`.             |
| `#{'order:' + #orderId}`            | String concatenation.                                  |
| `#{#count + 1}`                     | Arithmetic.                                            |
| `#approved == true`                 | Comparison (use raw SpEL in `condition`).              |
| `#status != 'rejected'`             | String literal in single quotes.                       |
| `#count > 0 and #count < 10`        | Boolean operators.                                     |

## Common SpEL pitfalls (read this before writing `defaultExpression`)

Spring SpEL's collection literals are NOT JSON. The two most common architect
mistakes both stem from this:

| You mean             | JSON                | Wrong SpEL | Correct SpEL                       |
|----------------------|---------------------|------------|------------------------------------|
| Empty **map / object** | `{}`              | `"{}"` ❌  | `null` (preferred) or `"{:}"`     |
| Empty **list / array** | `[]`              | n/a        | `"{}"` ✓ (yes — really)            |
| Inline map           | `{"a": 1}`          | `"{a:1}"` (depends) | `"{'a': 1}"` (string keys quoted) |
| Inline list          | `[1, 2, 3]`         | n/a        | `"{1, 2, 3}"`                      |

> `{}` in SpEL is an **empty `List`**, not an empty `Map`. Using `defaultExpression: "{}"` on a `java.util.Map` variable is the #1 source of `expected type object but got UnmodifiableRandomAccessList` failures at runtime. The engine self-heals this when the declared `javaClass` says `Map`, but emit the canonical form anyway.

Recommended defaults by `javaClass`:

| `javaClass`        | Use this `defaultExpression`                |
|--------------------|---------------------------------------------|
| `java.util.Map`    | `null` (declare it, populate later)         |
| `java.util.List`   | `null`, or `"{}"` if you really want `[]`   |
| `java.lang.String` | `"''"` for empty, `"'literal'"` for a value |
| `java.lang.Long`   | `"0"`, `"42"`, etc.                         |
| `java.lang.Boolean`| `"true"` / `"false"`                        |

If the variable is purely an output target (an activity will write into it),
always use `defaultExpression: null`. Never invent a default just to "fill the
field in".

## Sandbox restrictions

The following are **blocked** by `SecureSpelEvaluator`:

- Type lookup: `T(java.lang.System)` etc.
- Constructor invocation: `new java.io.File(...)`.
- Class introspection: `#x.class`, `getClass`, `getClassLoader`.
- Spring bean access: `@beanName`.

If you need a behaviour that requires any of these, fall back to a `tool` activity with an explicit plugin (e.g. `CodeExecPlugin`).

## Writing variables from plugin output

Declare an entry in the activity's `outputVariables`:

```json
{
  "id": "fetchUser",
  "type": "tool",
  "pluginName": "HttpRequestPlugin",
  "properties": { "method": "GET", "url": "#{'https://api/users/' + #userId}" },
  "outputVariables": [
    { "name": "userResponse", "javaClass": "java.util.Map", "defaultExpression": null, "required": false }
  ]
}
```

Downstream activities can then reference fields like `#{#userResponse.body}` or `#{#userResponse.status == 200}`.

## Examples in context

```json
{
  "variables": [
    { "name": "orderId",   "javaClass": "java.lang.String", "defaultExpression": null, "required": true },
    { "name": "threshold", "javaClass": "java.lang.Long",   "defaultExpression": "10", "required": false }
  ],
  "activities": [
    {
      "id": "fetch", "type": "tool", "pluginName": "AgentToolPlugin",
      "properties": { "toolName": "lookup_order", "input": "#{#orderId}" },
      "outputVariables": [{ "name": "order", "javaClass": "java.util.Map", "defaultExpression": null, "required": false }]
    }
  ],
  "transitions": [
    { "id": "t-big", "fromActivityId": "fetch", "toActivityId": "escalate", "condition": "#order.total > #threshold", "isErrorEdge": false, "matchesErrorClass": null }
  ]
}
```
