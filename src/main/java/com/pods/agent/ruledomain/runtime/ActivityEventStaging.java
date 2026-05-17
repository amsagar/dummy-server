package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.model.RuleActivityEvent;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;

/**
 * One-shot helper used by every BPMN delegate. Each delegate creates a
 * builder on entry, fills in input/output (or error) as it runs, and
 * calls {@link #stage()} once. The result lands in
 * {@link ActivityEventBuffer} and is flushed to the DB by
 * {@code BpmnRuntime} after the parent {@code rule_executions} row is
 * committed.
 *
 * <p>Reads {@code _executionId} from process variables (set by
 * {@code BpmnRuntime} before {@code startProcessInstanceByKey}) and
 * {@code loopCounter} (Flowable's per-iteration variable inside multi-
 * instance subprocesses).
 */
public final class ActivityEventStaging {

    private final long startTs;
    private final RuleActivityEvent.RuleActivityEventBuilder b = RuleActivityEvent.builder();
    private boolean staged = false;

    private ActivityEventStaging(DelegateExecution execution, String delegateBean) {
        this.startTs = System.currentTimeMillis();
        FlowElement fe = execution.getCurrentFlowElement();
        String activityId = fe == null ? "?" : fe.getId();
        String activityName = fe == null ? null : fe.getName();
        String activityType = fe == null ? null : fe.getClass().getSimpleName();

        b.executionId(ActivityEventBuffer.currentExecutionId())
                .processInstanceId(execution.getProcessInstanceId())
                .activityId(activityId)
                .activityName(activityName)
                .activityType(activityType)
                .delegateBean(delegateBean)
                .iterationIndex(currentIteration(execution))
                .startTs(startTs);
    }

    /** Open a staging context. Caller is responsible for calling
     *  {@link #stage()} on success/failure paths; the buffer ignores
     *  events when no execution is active so it's safe to call from
     *  isolated tests too. */
    public static ActivityEventStaging start(DelegateExecution execution, String delegateBean) {
        return new ActivityEventStaging(execution, delegateBean);
    }

    public ActivityEventStaging input(String inputJson) {
        b.inputJson(inputJson);
        return this;
    }

    public ActivityEventStaging output(String outputJson) {
        b.outputJson(outputJson);
        return this;
    }

    public ActivityEventStaging error(String code, String message) {
        b.errorCode(code).errorMessage(message);
        return this;
    }

    /** Persist the event into the thread-local buffer. Idempotent: only
     *  the first call has effect, so it's safe to invoke from both the
     *  happy and error paths. */
    public void stage() {
        if (staged) return;
        staged = true;
        long endTs = System.currentTimeMillis();
        b.endTs(endTs).durationMs((int) Math.min(endTs - startTs, Integer.MAX_VALUE));
        ActivityEventBuffer.add(b.build());
    }

    /** Read Flowable's per-iteration loopCounter variable. Returns
     *  {@code null} for non-multi-instance activities. */
    private static Integer currentIteration(DelegateExecution execution) {
        try {
            Object lc = execution.getVariableLocal("loopCounter");
            if (lc instanceof Integer i) return i;
            if (lc instanceof Number n) return n.intValue();
        } catch (Exception ignored) {
        }
        return null;
    }
}
