# Third-Party Notices

This project vendors source files from third-party open source projects.
Each file retains its original copyright header. The list below identifies
each vendored file and its upstream origin.

---

## Joget Community Edition

- **Project:** Joget DX (joget/jw-community)
- **Upstream:** https://github.com/jogetoss/jw-community
- **License:** GNU General Public License v3.0 — https://www.gnu.org/licenses/gpl-3.0.html
- **Vendored from branch / commit:** `9.0-RELEASE` @ `59c2c1ca5ca33506984f7a98e043a0e187a00ebd`

> **License obligation.** The GPLv3 is a strong copyleft license. Because
> source files from Joget have been incorporated into this project, the
> derivative work as a whole must also be distributed under GPLv3 if and
> when distributed. Internal-only use is permitted without distribution
> obligations. Verify with legal/compliance before any external release.

### Vendored files

| Vendored path (this repo) | Upstream path (jw-community) | Notes |
|---|---|---|
| `src/main/java/com/pods/agent/workflow/joget/model/WorkflowVariable.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowVariable.java` | repackaged; no semantic changes |
| `src/main/java/com/pods/agent/workflow/joget/model/WorkflowProcess.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowProcess.java` | repackaged; stripped `WorkflowUtil.translateProcessLabel` calls and `ApplicationContext.getBean` lazy variable loading |
| `src/main/java/com/pods/agent/workflow/joget/model/WorkflowActivity.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowActivity.java` | repackaged; same strips as WorkflowProcess; preserves `TYPE_NORMAL`/`TYPE_TOOL`/`TYPE_ROUTE`/`TYPE_SUBFLOW` constants |
| `src/main/java/com/pods/agent/workflow/joget/model/WorkflowAssignment.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowAssignment.java` | repackaged; stripped `WorkflowUtil.translateProcessLabel` calls |
| `src/main/java/com/pods/agent/workflow/joget/model/WorkflowProcessLink.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowProcessLink.java` | repackaged; no semantic changes |
| `src/main/java/com/pods/agent/workflow/joget/model/WorkflowParticipant.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowParticipant.java` | repackaged; no semantic changes |
| `src/main/java/com/pods/agent/workflow/joget/model/DecisionResult.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/DecisionResult.java` | repackaged; no semantic changes |
| `src/main/java/com/pods/agent/workflow/joget/plugin/ApplicationPlugin.java` | `wflow-plugin-base/src/main/java/org/joget/plugin/base/ApplicationPlugin.java` | repackaged; raw `Map` tightened to `Map<String, Object>` |
| `src/main/java/com/pods/agent/workflow/joget/plugin/DecisionPlugin.java` | `wflow-wfengine/src/main/java/org/joget/workflow/model/DecisionPlugin.java` | repackaged; dropped `extends PropertyEditable` (Joget plugin lifecycle not vendored) |
| `src/main/java/com/pods/agent/workflow/joget/expression/SecureSpelEvaluator.java` | `wflow-core/src/main/java/org/joget/apps/app/lib/ExpressionHashVariable.java` | repackaged; the security-critical SpEL setup is preserved (`NoTypeLocator`, `SecureMethodResolver`, `SecurePropertyAccessor`, `NoBeanResolver`, `NoConstructorResolver`); the Joget hash-variable plugin lifecycle (`DefaultHashVariablePlugin`, `AppUtil.readPluginResource`, `LogUtil.error`) is removed; parse / evaluation errors are surfaced as `Result.failure(...)` instead of being swallowed |

### Reference (NOT vendored, used only as design inspiration)

These upstream files were studied during the design of the agent's workflow
engine but no source code was copied. The agent's `engine/` package is
original code.

- `wflow-shark/src/main/java/.../WorkflowDODSPersistentManager.java` — informed the `process_inst` / `activity_inst` / state-machine design
- `wflow-shark/src/main/java/.../DeadlineChecker.java` — informed the timer-event pattern
- `wflow-shark/src/main/java/.../CustomWfActivityImpl.java` — informed sub-flow scope semantics
- `wflow-core/src/main/java/.../AuditTrailManager.java` — informed the audit row shape

### Excluded from vendoring

The following Joget components were **not** vendored. The agent provides clean
Spring Boot 4 / WebFlux replacements for each.

- `wflow-shark` and `wflow-shark-jakarta` (the Enhydra Shark engine + DODS persistence)
- `wflow-consoleweb` (JSP / Servlet front end)
- `wflow-plugin-base` plugin-lifecycle classes beyond `ApplicationPlugin` (e.g. `DefaultPlugin`, `ExtDefaultPlugin`, `DefaultApplicationPlugin`, `PluginProperty`, `PluginManager`)
- `wflow-core` form / list / userview engines

---

## How to update vendored files

If a vendored file diverges from upstream:

1. Re-pull upstream from the documented commit, diff against the local copy,
   re-apply the strip notes from the file's vendoring header.
2. Update the `Vendored from branch / commit` line above to the new SHA.
3. Run `mvn clean test` to confirm the engine still works against the new
   contract.
