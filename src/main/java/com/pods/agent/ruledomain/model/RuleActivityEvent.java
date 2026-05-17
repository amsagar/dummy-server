package com.pods.agent.ruledomain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One BPMN activity invocation in a rule-domain run. Captured at the
 * delegate level so we know the exact input args + output value (not just
 * Flowable's structural history). Multi-instance subprocesses produce one
 * row per iteration, distinguished by {@code iterationIndex}.
 *
 * @see com.pods.agent.ruledomain.repository.RuleActivityEventRepository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleActivityEvent {
    private String id;
    private String executionId;
    private String processInstanceId;
    private String activityId;
    private String activityName;
    private String activityType;
    /** Bean name from {@code flowable:delegateExpression}, e.g.
     *  {@code "toolCallDelegate"}. Lets the UI filter by delegate. */
    private String delegateBean;
    /** {@code null} for non-multi-instance activities; 0..N for each
     *  iteration of a multi-instance subprocess. */
    private Integer iterationIndex;
    /** JSON-encoded input args the delegate received (e.g. the resolved
     *  argTemplate for toolCallDelegate). */
    private String inputJson;
    /** JSON-encoded value the delegate bound to {@code outputBinding} (or
     *  the postTransform result, if present). {@code null} when the task
     *  threw before producing an output. */
    private String outputJson;
    /** BpmnError code if the delegate threw, e.g. {@code "FEEL_EVAL_FAILED"}. */
    private String errorCode;
    private String errorMessage;
    private long startTs;
    private Long endTs;
    private Integer durationMs;
    private long createdAt;
}
