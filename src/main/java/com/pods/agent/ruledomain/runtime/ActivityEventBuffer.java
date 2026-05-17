package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.model.RuleActivityEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-local buffer for {@link RuleActivityEvent} rows produced by
 * BPMN delegates during a single {@code BpmnRuntime.execute} call.
 *
 * <p>Delegates can't insert directly because {@code rule_activity_events}
 * has a foreign key to {@code rule_executions.id}, and the execution row
 * isn't committed until {@code BpmnRuntime.persist} runs at end-of-run.
 * So each delegate stages its event here; {@code BpmnRuntime} flushes
 * the buffer to the repository after the parent execution row is saved.
 *
 * <p>Thread-confined because synchronous Flowable execution runs every
 * delegate on the caller thread (the same one calling
 * {@code runtimeService.startProcessInstanceByKey}). The buffer is set
 * before {@code startProcessInstanceByKey} and cleared in a finally.
 */
public final class ActivityEventBuffer {

    private static final ThreadLocal<List<RuleActivityEvent>> BUFFER = new ThreadLocal<>();
    private static final ThreadLocal<String> EXECUTION_ID = new ThreadLocal<>();

    private ActivityEventBuffer() {}

    /** Open a buffer for this thread. {@code executionId} is the
     *  pre-allocated {@code rule_executions.id} that activity events
     *  will reference once {@code BpmnRuntime} inserts the parent row. */
    public static void open(String executionId) {
        BUFFER.set(new ArrayList<>());
        EXECUTION_ID.set(executionId);
    }

    /** True when {@link #open} has been called and {@link #close} has not. */
    public static boolean isActive() {
        return BUFFER.get() != null;
    }

    /** Pre-allocated rule_executions.id, set by {@code BpmnRuntime} so
     *  delegates can stamp it on their events. Returns {@code null} when
     *  no buffer is active (delegate invoked outside a BPMN run, e.g.
     *  during a test). */
    public static String currentExecutionId() {
        return EXECUTION_ID.get();
    }

    /** Stage an event. No-op when no buffer is active (e.g. delegate
     *  invoked in isolation by a test). */
    public static void add(RuleActivityEvent event) {
        List<RuleActivityEvent> list = BUFFER.get();
        if (list == null) return;
        list.add(event);
    }

    /** Return the staged events and clear the thread-local. Always paired
     *  with {@link #open} via try/finally in {@code BpmnRuntime}. */
    public static List<RuleActivityEvent> close() {
        List<RuleActivityEvent> list = BUFFER.get();
        BUFFER.remove();
        EXECUTION_ID.remove();
        return list == null ? Collections.emptyList() : list;
    }
}
