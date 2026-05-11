# Transitions and routing

A `TransitionDto` connects two activities and may carry a guard expression.

```
TransitionDto {
  id                : unique non-blank string
  fromActivityId    : id of the source activity
  toActivityId      : id of the destination activity
  condition         : SecureSpel expression evaluated against the variable scope; null = unconditional
  isErrorEdge       : true if this edge is taken only when the source activity fails
  matchesErrorClass : "EXPRESSION" | "VALIDATION" | "TIMEOUT" | "TOOL" | "SUBFLOW" | "UNCAUGHT" | null
}
```

## Engine evaluation order

After an activity completes, `RouteResolver` walks its outgoing transitions:

### On success
1. Skip every edge with `isErrorEdge: true`.
2. For each remaining edge: if `condition` is null, take it. Else evaluate it; take only if it's `true`.
3. Multiple matches → AND-split: all targets enqueue.
4. Zero matches and the activity is not `isEnd` → workflow stalls (terminated).

### On failure
1. Take only edges with `isErrorEdge: true`.
2. If `matchesErrorClass` is set, the source activity's `errorClass` must equal it.
3. Edges with no `condition` are taken unconditionally; conditioned edges are filtered by SpEL.

## Patterns

### Linear (unconditional)

```json
{ "id": "t1", "fromActivityId": "a", "toActivityId": "b", "condition": null, "isErrorEdge": false, "matchesErrorClass": null }
```

### XOR decision

A `route` activity with two conditioned outgoing edges. Conditions must be mutually exclusive.

```json
[
  { "id": "t-yes", "fromActivityId": "decide", "toActivityId": "approve", "condition": "#approved == true",  "isErrorEdge": false, "matchesErrorClass": null },
  { "id": "t-no",  "fromActivityId": "decide", "toActivityId": "reject",  "condition": "#approved != true", "isErrorEdge": false, "matchesErrorClass": null }
]
```

### AND-split + AND-join

Source has multiple outgoing edges; the join activity sets `andJoin: true`.

```json
{ "id": "join", "name": "Join", "type": "route", "andJoin": true, "properties": {}, "isStart": false, "isEnd": false }
```

### Error recovery

A `tool` step with a tool-error fallback to a recovery route, plus a generic catch-all.

```json
[
  { "id": "t-ok",       "fromActivityId": "callApi", "toActivityId": "saveResult", "condition": null, "isErrorEdge": false, "matchesErrorClass": null },
  { "id": "t-err-tool", "fromActivityId": "callApi", "toActivityId": "handleApiErr", "condition": null, "isErrorEdge": true,  "matchesErrorClass": "TOOL" },
  { "id": "t-err-any",  "fromActivityId": "callApi", "toActivityId": "handleAnyErr", "condition": null, "isErrorEdge": true,  "matchesErrorClass": null }
]
```

## ErrorClass values

| Constant     | Raised when                                                       |
|--------------|-------------------------------------------------------------------|
| `EXPRESSION` | SecureSpel parse / eval failed in a property or condition.        |
| `VALIDATION` | Required variable missing, or variable type mismatch.             |
| `TIMEOUT`    | Activity exceeded `deadlineExpression`.                           |
| `TOOL`       | Plugin executed but threw or signalled logical failure.           |
| `SUBFLOW`    | Child subflow ended in `closed.terminated`.                       |
| `UNCAUGHT`   | Anything not classified above.                                    |

## Validation gotchas

- Both `fromActivityId` and `toActivityId` must reference activities that exist in the same `ProcessDefDto`. Dangling endpoints throw at `ProcessDefinition.build()`.
- `id` must be non-blank. Duplicates are tolerated by the DTO but make debugging painful — use stable, unique ids.
- A workflow with no path from the start activity to any `isEnd: true` activity will stall.
