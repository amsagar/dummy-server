package com.pods.agent.ruledomain.runtime;

import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.FeelEngine;
import org.springframework.stereotype.Component;
import scala.util.Either;

import java.util.Map;

/**
 * Thin Java-friendly wrapper around the Scala-flavored Camunda FEEL engine.
 * All BPMN delegates that evaluate user-authored expressions route through
 * here so the Scala/Either interop is in one place.
 */
@Component
@Slf4j
public class FeelHelper {

    private final FeelEngine engine;

    public FeelHelper(FeelEngine engine) {
        this.engine = engine;
    }

    /**
     * Evaluate a FEEL expression against the given variable map.
     *
     * @throws FeelEvaluationException with the engine's diagnostic when evaluation fails
     */
    public Object eval(String expression, Map<String, Object> context) {
        if (expression == null) return null;
        @SuppressWarnings("unchecked")
        Either<FeelEngine.Failure, Object> result = engine.evalExpression(expression, context);
        if (result.isRight()) {
            return result.right().get();
        }
        FeelEngine.Failure f = result.left().get();
        log.debug("FEEL evaluation failed: expr='{}' message='{}'", expression, f.message());
        throw new FeelEvaluationException(expression, f.message());
    }

    /** Like {@link #eval} but returns {@code defaultValue} on failure rather than throwing. */
    public Object evalOrDefault(String expression, Map<String, Object> context, Object defaultValue) {
        try {
            return eval(expression, context);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    public static class FeelEvaluationException extends RuntimeException {
        private final String expression;

        public FeelEvaluationException(String expression, String message) {
            super("FEEL eval failed for `" + expression + "`: " + message);
            this.expression = expression;
        }

        public String getExpression() {
            return expression;
        }
    }
}
