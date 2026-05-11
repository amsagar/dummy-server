# Phase 4 — old toolchain deletion: deferred (with detailed scope)

The plan called for deleting the old toolchain orchestrator and supporting
code. After full audit, **Phase 4 is a multi-week migration project, not a
deletion pass**. This document is the scope a future session will execute
against.

## Why it isn't a simple delete

The "old toolchain" is **not** an internal implementation detail — it's a
**product feature** of the chat agent. ToolChains have a designer mode in
chat, get matched against user intents, drive parameter extraction from
free-form prompts, and are referenced by the agent's prompts and skills.

Concrete coupling (file:line evidence from the dependency audit):

| Caller (chat/agent layer) | What it uses from old toolchain | Lines of code |
|---|---|---|
| `AgentRuntimeService` | `ToolChainRuntimeService`, `ToolChainService.findIntentMatches`, `ChainParameterExtractor`, the `toolchain-architect` skill — ~10+ call sites including the chat turn loop | 1567 |
| `AgentOrchestrator` | hardcoded `TOOLCHAIN_ARCHITECT_SKILL` const, "ToolChain Designer Mode" prompt block, structured-JSON envelope contract | 518 |
| `LlmArgResolver` | `ToolChainRuntimeService.resolveModelRefForSynthesis` | 175 |
| `ToolChainController` | `ToolChainRuntimeService` (HTTP API surface for chat clients) | (not measured) |
| `ToolChainConfigChatService`, `SystemToolChainAsyncService`, `ToolChainArchitectAgentService` | the `toolchain-architect` skill plus shared services | (not measured) |
| `ToolChainMappingEditorService`, `MappingValidator`, `ExpressionValidator` | `ArgMappingResolver`, `BooleanExpressionEvaluator` (the validation layer for the toolchain designer in chat) | (not measured) |
| `ToolChainRuntimeService` itself | the orchestrator being replaced | 2133 |

That's **roughly 4,400 LoC of chat/agent code** with toolchain references,
not counting the toolchain runtime itself.

## What a real Phase 4 looks like

A migration session, not a deletion session:

1. **Map product surfaces.** Decide whether each chat-side feature
   (intent matching, designer mode, architect skill, parameter extraction,
   approval flow) survives as-is, becomes a new "workflow" surface, or is
   retired. Most likely outcome: rename "ToolChain" → "Workflow" across
   product copy, and re-implement the designer-mode chat flow against the
   new engine.
2. **Re-implement intent matching against `ProcessDefinition`** so chat can
   match user prompts to workflows. Today this is in
   `ToolChainService.findIntentMatches`; needs an equivalent that reads
   from `agent.process_def`.
3. **Re-implement parameter extraction** so the chat agent can populate
   `ProcessDefinition.variables` from free-form user prompts. Today this is
   in `ChainParameterExtractor` against the toolchain's `paramSchema`.
4. **Migrate the agent prompts** that reference "ToolChain" / "ToolChain
   Designer Mode" / `toolchain-architect` skill to their workflow
   equivalents. New skill: `workflow-architect` (or merge concepts).
5. **Data migration.** Existing rows in `agent.tool_chains`,
   `agent.tool_chain_runs`, `agent.tool_chain_run_steps`, etc. — translate
   to `agent.process_def`/`process_inst`/`activity_inst`, or accept that
   historical runs aren't queryable through the new UI.
6. **Tests.** ~20 toolchain-related tests need replacement counterparts
   against the workflow engine.
7. **Then** the deletion: `ToolChainRuntimeService`, `ArgMapping*`,
   `BooleanExpressionEvaluator`, `JSONata4Java` dep, `toolchain-templates/`,
   `default-skills/toolchain-architect/`, the relevant controllers, and
   ~20 obsolete tests.

Estimated effort: **5–10 working days** of careful work, with a code
review at every step because the chat product is the primary user-facing
surface.

## Until then

Both engines coexist:
- `/api/toolchain/*` — old, still drives the chat-product designer mode
- `/api/v1/workflow/*` — new, drives the standalone Workflows board

The new Workflows page is in the sidebar between "ToolChains" and
"Decision Tables". Users can author standalone workflows with the new
engine without affecting chat-driven toolchain flows.

If you want me to start the migration now, the first session should focus
on items 1–2 (product-surface decisions + intent matching). The actual
deletion only happens after items 1–6 are complete.
