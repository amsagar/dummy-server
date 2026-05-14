# SpEL sandbox — what the evaluator forbids and how to work around it

Workflow expressions (`#{...}` in any property string, `condition` on transitions, `defaultExpression` on variables) run through `SecureSpelEvaluator`, a hardened SpEL evaluator that strips access to Java type machinery. Authoring code that hits these traps yields a `VALIDATION` error at runtime — and now, before runtime, a `forbidden_spel_token` validator rejection.

## Forbidden constructs

Authoritative source: [`doc/spel-rules.json`](../doc/spel-rules.json). Each rule is enforced by the validator.

| Construct | Why it fails | Replacement |
|---|---|---|
| `T(java.lang.String)` and similar `T(...)` | Type lookups are forbidden. SpEL throws `Access to types is forbidden` at parse time. | Compute the value in a `CodeExecPlugin` activity (full Java available) and read it as an output variable. Example: instead of `T(java.time.LocalDate).now()`, set `todayIso` in CodeExec via `return java.time.LocalDate.now().toString();`. |
| `new Foo(...)` | Constructor calls are forbidden. | Construct in a CodeExec step; pass the result via a workflow variable. |
| `@beanName` | Bean references are forbidden. | Beans are not reachable from workflow SpEL. Use `AgentToolPlugin` / `SkillToolPlugin` / `CodeExecPlugin` to invoke the capability. |
| `someValue.getClass()` | Reflection is forbidden. | Branch on the value itself (a field check, a string compare). |
| `someValue.class` or `someValue.classLoader` | Same as above. | Drop the access. |

## The `{}` trap (list vs map)

```spel
#{ {} }                  // SpEL parses this as an EMPTY LIST, not an empty Map.
#{ {:} }                 // This is the empty MAP literal.
#{ {'k': 'v'} }          // Map with one entry.
#{ ['a', 'b'] }          // List with two strings (same as {'a','b'} — confusingly).
```

If you write an Elvis fallback like `(#maybeNull ?: {})` thinking it produces an empty Map, you'll get an empty List instead. Downstream code that calls `.get(...)` will fail. Use `{:}` when you mean "empty map".

## Allowed and encouraged

```spel
#myVar                       // workflow variable reference (always # prefix)
#myVar?.someField            // safe navigation; null short-circuits
#myVar?.nested?.field        // chained safe navigation
(#optional ?: 'default')     // Elvis with default
#{ {'k': #someVar} }         // inline map literal with variable interpolation
#list.size()                 // method calls on collection types
#someStr.startsWith('foo')   // method calls on String
#someStr.length()
```

## Common patterns

### Inline map for tool input

```json
"input": "#{ {'orderId': #orderId, 'context': {'env': 'prod'}} }"
```

### Conditional branching in a transition

```json
{ "fromActivityId": "fetchOrder", "toActivityId": "approve",
  "condition": "#order?.status == 'PENDING'",
  "trigger": "ON_SUCCESS" }
```

### Default expression for an optional variable

```json
{ "name": "retryCount", "javaClass": "java.lang.Long", "defaultExpression": "0" }
```

## Failures route via `ON_VALIDATION_ERROR`

When SpEL evaluation fails (either at parse — `T(...)`, `new`, `@bean` — or at runtime — `.getClass()`, `.class`), the engine emits `ErrorClass.VALIDATION` for that activity. A matching transition with `trigger: ON_VALIDATION_ERROR` (or the generic `ON_ERROR`) catches it. Without such an edge the run stalls and gets marked `error`.

## The mistake the validator now catches

`WorkflowJsonValidator.forbidden_spel_token` scans every expression string against the patterns in `doc/spel-rules.json`. The fix recipe from `doc/validator-codes.json` is appended to retry feedback so the builder LLM sees what to do, not just what's wrong.
