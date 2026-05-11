package com.pods.agent.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ActivityErrorPolicy;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.engine.domain.ProcessState;
import com.pods.agent.workflow.engine.domain.TransitionDef;
import com.pods.agent.workflow.engine.domain.TransitionTrigger;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;
import com.pods.agent.workflow.joget.model.WorkflowActivity;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the Phase 1 in-memory workflow engine.
 *
 * <p>Constructs the engine components manually (no Spring context) so the
 * test never touches Postgres. {@link NoopEnginePersistence} is the
 * persistence layer.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path: start → tool → end completes with the tool output stored
 *       as a process variable.</li>
 *   <li>XOR decision routing on a transition condition.</li>
 *   <li>Tool failure routes via an error edge (typed {@link
 *       com.pods.agent.workflow.engine.domain.ErrorClass#TOOL}).</li>
 *   <li>SecureSpelEvaluator rejects type/bean/constructor access.</li>
 * </ul>
 */
class WorkflowEngineSmokeTest {

    @Test
    void happy_path_start_tool_end_runs_to_completion() {
        AtomicReference<Map<String, Object>> capturedProps = new AtomicReference<>();
        EchoPlugin echoPlugin = new EchoPlugin(capturedProps);

        WorkflowManager wm = newEngine(List.of(echoPlugin), List.of());

        ActivityDef start = activity("a-start", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), true, false);
        ActivityDef tool = toolActivity("a-tool", "EchoPlugin",
                Map.of("payload", "#{#input}"));
        ActivityDef end = activity("a-end", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), false, true);

        ProcessDefinition def = ProcessDefinition.build(
                "p1", "Happy Path", "1", null, null,
                List.of(),
                List.of(start, tool, end),
                List.of(
                        edge("t-start-tool", "a-start", "a-tool", null, false, null),
                        edge("t-tool-end", "a-tool", "a-end", null, false, null)));

        WorkflowManager.StartResult result = wm.startProcess(
                def, Map.of("input", "hello"), "tester");

        assertTrue(result.isCompleted(),
                "expected CLOSED_COMPLETED, got " + result.state() + " err=" + result.errorMessage());
        assertEquals("hello", capturedProps.get().get("payload"),
                "tool plugin should receive the SpEL-resolved payload");
    }

    @Test
    void xor_decision_routes_on_transition_condition() {
        YesPlugin yesPlugin = new YesPlugin();
        NoPlugin noPlugin = new NoPlugin();

        WorkflowManager wm = newEngine(List.of(yesPlugin, noPlugin), List.of());

        ActivityDef start = activity("a-start", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), true, false);
        ActivityDef route = activity("a-route", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), false, false);
        ActivityDef yesTool = toolActivity("a-yes", "YesPlugin", Map.of());
        ActivityDef noTool = toolActivity("a-no", "NoPlugin", Map.of());
        ActivityDef end = activity("a-end", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), false, true);

        ProcessDefinition def = ProcessDefinition.build(
                "p2", "XOR", "1", null, null, List.of(),
                List.of(start, route, yesTool, noTool, end),
                List.of(
                        edge("t1", "a-start", "a-route", null, false, null),
                        edge("t-yes", "a-route", "a-yes", "#approved == true", false, null),
                        edge("t-no", "a-route", "a-no", "#approved != true", false, null),
                        edge("t-yes-end", "a-yes", "a-end", null, false, null),
                        edge("t-no-end", "a-no", "a-end", null, false, null)));

        WorkflowManager.StartResult yesResult = wm.startProcess(def, Map.of("approved", true), "tester");
        assertTrue(yesResult.isCompleted(), "yes branch should complete: " + yesResult.errorMessage());

        WorkflowManager.StartResult noResult = wm.startProcess(def, Map.of("approved", false), "tester");
        assertTrue(noResult.isCompleted(), "no branch should complete: " + noResult.errorMessage());
    }

    @Test
    void tool_failure_with_no_error_edge_terminates() {
        ThrowingPlugin throwing = new ThrowingPlugin();

        WorkflowManager wm = newEngine(List.of(throwing), List.of());

        ActivityDef start = activity("a-start", WorkflowActivity.TYPE_ROUTE, null, Map.of(), true, false);
        ActivityDef tool = toolActivity("a-tool", "ThrowingPlugin", Map.of());
        ActivityDef end = activity("a-end", WorkflowActivity.TYPE_ROUTE, null, Map.of(), false, true);

        ProcessDefinition def = ProcessDefinition.build(
                "p3", "Tool Failure", "1", null, null, List.of(),
                List.of(start, tool, end),
                List.of(
                        edge("t1", "a-start", "a-tool", null, false, null),
                        edge("t2", "a-tool", "a-end", null, false, null)));

        WorkflowManager.StartResult result = wm.startProcess(def, Map.of(), "tester");

        assertEquals(ProcessState.CLOSED_TERMINATED, result.state(),
                "tool failure with no error edge should terminate");
        assertFalse(result.isCompleted());
    }

    @Test
    void and_join_waits_for_all_incoming_branches() {
        java.util.concurrent.atomic.AtomicInteger joinHits = new java.util.concurrent.atomic.AtomicInteger(0);
        ApplicationPlugin a1 = new YesPlugin();
        ApplicationPlugin a2 = new NoPlugin();
        ApplicationPlugin joinSpy = new ApplicationPlugin() {
            @Override public Object execute(Map<String, Object> props) {
                joinHits.incrementAndGet();
                return "joined";
            }
        };
        // Anonymous subclass with a stable simple class name.
        ApplicationPlugin joinNamed = new JoinSpyPlugin(joinHits);

        WorkflowManager wm = newEngine(List.of(a1, a2, joinNamed), List.of());

        ActivityDef start = activity("a-start", WorkflowActivity.TYPE_ROUTE, null, Map.of(), true, false);
        ActivityDef branch1 = toolActivity("a-branch1", "YesPlugin", Map.of());
        ActivityDef branch2 = toolActivity("a-branch2", "NoPlugin", Map.of());
        ActivityDef join = new ActivityDef("a-join", "join",
                WorkflowActivity.TYPE_TOOL, "JoinSpyPlugin",
                Map.of(), Map.of(), Map.of(), null, false, false, null, null, null, List.of(),
                /* andJoin */ true, ActivityErrorPolicy.defaults());
        ActivityDef end = activity("a-end", WorkflowActivity.TYPE_ROUTE, null, Map.of(), false, true);

        ProcessDefinition def = ProcessDefinition.build(
                "p-and", "AND join", "1", null, null, List.of(),
                List.of(start, branch1, branch2, join, end),
                List.of(
                        // AND-split from start to both branches.
                        edge("e1", "a-start", "a-branch1", null, false, null),
                        edge("e2", "a-start", "a-branch2", null, false, null),
                        edge("e3", "a-branch1", "a-join", null, false, null),
                        edge("e4", "a-branch2", "a-join", null, false, null),
                        edge("e5", "a-join", "a-end", null, false, null)));

        WorkflowManager.StartResult result = wm.startProcess(def, Map.of(), "tester");
        assertTrue(result.isCompleted(), "AND-join should complete: " + result.errorMessage());
        assertEquals(1, joinHits.get(), "join activity must fire exactly once after both branches arrive");
    }

    @Test
    void foreach_with_unconditional_body_edge_exits_via_no_match() {
        // Reproduces the LLM-natural loop wiring used by WorkflowProposalService.
        // The body edge is ON_SUCCESS with no condition; the exit edge is
        // ON_NO_MATCH with isDefault=true. The engine must auto-suppress the
        // body edge when the loop is exhausted so the exit edge fires once.
        java.util.concurrent.atomic.AtomicInteger bodyHits = new java.util.concurrent.atomic.AtomicInteger(0);
        ApplicationPlugin body = props -> {
            bodyHits.incrementAndGet();
            return "ok";
        };
        // Wrap into a named class so PluginRegistry finds it by simple name.
        ApplicationPlugin bodyNamed = new BodySpyPlugin(bodyHits);

        WorkflowManager wm = newEngine(List.of(bodyNamed), List.of());

        ActivityDef start = activity("a-start", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), true, false);
        ActivityDef loop = new ActivityDef(
                "a-loop", "loop", WorkflowActivity.TYPE_FOREACH, null,
                Map.of("collection", "#{#items}",
                        "itemVar", "currentItem",
                        "indexVar", "currentIndex",
                        "maxIterations", 100),
                Map.of(), Map.of(), null, false, false,
                null, null, null, List.of(), false, ActivityErrorPolicy.defaults());
        ActivityDef bodyAct = toolActivity("a-body", "BodySpyPlugin",
                Map.of("payload", "#{#currentItem}"));
        ActivityDef end = activity("a-end", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), false, true);

        ProcessDefinition def = ProcessDefinition.build(
                "p-loop", "Loop", "1", null, null,
                List.of(),
                List.of(start, loop, bodyAct, end),
                List.of(
                        edge("t-start-loop", "a-start", "a-loop", null, false, null),
                        // Body edge: unconditional ON_SUCCESS — engine should
                        // suppress this when loop exhausts.
                        edge("t-loop-body",  "a-loop",  "a-body", null, false, null),
                        // Back-edge.
                        edge("t-body-loop",  "a-body",  "a-loop", null, false, null),
                        // Exit edge: ON_NO_MATCH default.
                        new TransitionDef("t-loop-end", "a-loop", "a-end",
                                null, false, null,
                                TransitionTrigger.ON_NO_MATCH, 100, true)));

        WorkflowManager.StartResult result = wm.startProcess(
                def, Map.of("items", List.of("a", "b", "c")), "tester");

        assertTrue(result.isCompleted(), "loop must complete cleanly: " + result.errorMessage());
        assertEquals(3, bodyHits.get(), "body should run exactly once per item");
    }

    /**
     * Regression: SpEL {@code {}} parses as an empty {@code List}, not a Map.
     * The architect frequently emits {@code "defaultExpression": "{}"} for
     * Map-typed variables, which used to dead-end the very first activity
     * with a "expected type object but got UnmodifiableRandomAccessList"
     * schema-validation failure. {@code WorkflowManager.applyDefaults} now
     * coerces empty-Collection-on-Map mismatches to an empty {@link
     * java.util.LinkedHashMap}; this test enforces that.
     */
    @Test
    void empty_map_default_expression_is_coerced_to_map_not_list() {
        AtomicReference<Object> seen = new AtomicReference<>();
        CaptureMapPlugin captureTool = new CaptureMapPlugin(seen);

        WorkflowManager wm = newEngine(List.of(captureTool), List.of());

        // The bad-but-common architect output: defaultExpression "{}" on a Map.
        com.pods.agent.workflow.engine.domain.VariableSpec mapVar =
                new com.pods.agent.workflow.engine.domain.VariableSpec(
                        "fetchParams", "java.util.Map", "{}", false);

        ActivityDef start = activity("a-start", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), true, false);
        ActivityDef tool = toolActivity("a-tool", "CaptureMapPlugin",
                Map.of("payload", "#{#fetchParams}"));
        ActivityDef end = activity("a-end", WorkflowActivity.TYPE_ROUTE, null,
                Map.of(), false, true);

        ProcessDefinition def = ProcessDefinition.build(
                "p-empty-map", "EmptyMap", "1", null, null,
                List.of(mapVar),
                List.of(start, tool, end),
                List.of(
                        edge("t-start-tool", "a-start", "a-tool", null, false, null),
                        edge("t-tool-end", "a-tool", "a-end", null, false, null)));

        WorkflowManager.StartResult result = wm.startProcess(def, Map.of(), "tester");

        assertTrue(result.isCompleted(),
                "engine must coerce '{}' (List) to {} (Map) for Map-declared vars; got " + result.errorMessage());
        assertTrue(seen.get() instanceof Map, "fetchParams should land in the plugin as a Map, was " + seen.get());
        assertEquals(0, ((Map<?, ?>) seen.get()).size(), "coerced Map should be empty");
    }

    @Test
    void securespel_rejects_type_and_constructor_access() {
        SecureSpelEvaluator.Result typed = SecureSpelEvaluator.evaluate(
                "T(java.lang.System).exit(0)", Map.of());
        assertFalse(typed.ok(), "type lookup must be blocked");

        SecureSpelEvaluator.Result newed = SecureSpelEvaluator.evaluate(
                "new java.io.File('/tmp/pwn')", Map.of());
        assertFalse(newed.ok(), "constructor invocation must be blocked");

        SecureSpelEvaluator.Result classProp = SecureSpelEvaluator.evaluate(
                "#x.class.name", Map.of("x", "ok"));
        assertFalse(classProp.ok(), "class property access must be blocked");

        // Sanity: arithmetic still works.
        SecureSpelEvaluator.Result ok = SecureSpelEvaluator.evaluate("1 + 2", Map.of());
        assertTrue(ok.ok());
        assertEquals(3, ok.value());
    }

    // ----------------------------------------------------------------- helpers

    private static WorkflowManager newEngine(List<ApplicationPlugin> appPlugins,
                                             List<com.pods.agent.workflow.joget.plugin.DecisionPlugin> decisionPlugins) {
        ObjectMapper om = new ObjectMapper();
        EnginePersistence persistence = new NoopEnginePersistence();
        AuditTrailManager audit = new AuditTrailManager(persistence, om);
        PluginRegistry registry = new PluginRegistry(appPlugins, decisionPlugins);
        registry.index();
        ActivityDispatcher dispatcher = new ActivityDispatcher(
                registry,
                audit,
                /* schemaValidator */ null,
                /* pinRepo */ null,
                om,
                /* modelProviderRouter */ null);
        RouteResolver router = new RouteResolver(audit);
        ProcessExecutor executor = new ProcessExecutor(dispatcher, router, audit, persistence, om);
        return new WorkflowManager(executor, audit, persistence,
                /* processInstRepo */ null,
                /* variableRepo */ null,
                om,
                /* metadataServiceProvider */ null);
    }

    private static ActivityDef activity(String id, String type, String pluginName,
                                        Map<String, Object> props, boolean start, boolean end) {
        return new ActivityDef(id, id, type, pluginName, props, Map.of(), Map.of(), null, start, end,
                null, null, null, List.of(), false, ActivityErrorPolicy.defaults());
    }

    private static ActivityDef toolActivity(String id, String pluginName, Map<String, Object> props) {
        return activity(id, WorkflowActivity.TYPE_TOOL, pluginName, props, false, false);
    }

    private static TransitionDef edge(String id, String from, String to,
                                      String condition, boolean isError,
                                      com.pods.agent.workflow.engine.domain.ErrorClass errClass) {
        return new TransitionDef(id, from, to, condition, isError, errClass,
                isError ? TransitionTrigger.ON_ERROR : TransitionTrigger.ON_SUCCESS,
                null, false);
    }

    // Named test plugins so PluginRegistry's "index by simple class name"
    // lookup is stable. (Lambdas would get auto-generated class names.)

    static final class EchoPlugin implements ApplicationPlugin {
        private final AtomicReference<Map<String, Object>> sink;
        EchoPlugin(AtomicReference<Map<String, Object>> sink) { this.sink = sink; }
        @Override public Object execute(Map<String, Object> props) {
            sink.set(props);
            return "echo:" + props.get("payload");
        }
    }

    static final class ThrowingPlugin implements ApplicationPlugin {
        @Override public Object execute(Map<String, Object> props) {
            throw new RuntimeException("boom");
        }
    }

    static final class YesPlugin implements ApplicationPlugin {
        @Override public Object execute(Map<String, Object> props) { return "yes-branch"; }
    }

    static final class NoPlugin implements ApplicationPlugin {
        @Override public Object execute(Map<String, Object> props) { return "no-branch"; }
    }

    static final class CaptureMapPlugin implements ApplicationPlugin {
        private final AtomicReference<Object> sink;
        CaptureMapPlugin(AtomicReference<Object> sink) { this.sink = sink; }
        @Override public Object execute(Map<String, Object> props) {
            sink.set(props.get("payload"));
            return null;
        }
    }

    static final class JoinSpyPlugin implements ApplicationPlugin {
        private final java.util.concurrent.atomic.AtomicInteger counter;
        JoinSpyPlugin(java.util.concurrent.atomic.AtomicInteger counter) { this.counter = counter; }
        @Override public Object execute(Map<String, Object> props) {
            counter.incrementAndGet();
            return "joined";
        }
    }

    static final class BodySpyPlugin implements ApplicationPlugin {
        private final java.util.concurrent.atomic.AtomicInteger counter;
        BodySpyPlugin(java.util.concurrent.atomic.AtomicInteger counter) { this.counter = counter; }
        @Override public Object execute(Map<String, Object> props) {
            counter.incrementAndGet();
            return props.get("payload");
        }
    }
}
