package com.pods.agent.ruledomain.runtime;

import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.FeelEngine;
import org.springframework.stereotype.Component;
import scala.Option;
import scala.util.Either;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            return toJava(result.right().get());
        }
        FeelEngine.Failure f = result.left().get();
        log.debug("FEEL evaluation failed: expr='{}' message='{}'", expression, f.message());
        throw new FeelEvaluationException(expression, f.message());
    }

    /**
     * Recursively convert Scala collection types returned by the Camunda FEEL
     * engine into plain Java equivalents. Without this, a FEEL list expression
     * returns a {@code scala.collection.immutable.Vector} that doesn't
     * implement {@link java.util.Collection}, which makes Flowable's
     * multi-instance {@code flowable:collection} expression fail with
     * "Couldn't resolve collection expression". Likewise nested maps would
     * surface as {@code scala.collection.immutable.Map} and break downstream
     * Jackson serialization / FEEL re-binding.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object toJava(Object value) {
        if (value == null) return null;
        if (value instanceof Option<?> opt) {
            return opt.isDefined() ? toJava(opt.get()) : null;
        }
        if (value instanceof scala.collection.Map<?, ?> sm) {
            Map<Object, Object> out = new LinkedHashMap<>();
            scala.collection.Iterator<scala.Tuple2<?, ?>> it = (scala.collection.Iterator) sm.iterator();
            while (it.hasNext()) {
                scala.Tuple2<?, ?> t = it.next();
                Object k = toJava(t._1());
                Object v = toJava(t._2());
                out.put(k, v);
            }
            return out;
        }
        if (value instanceof scala.collection.Iterable<?> sc) {
            List<Object> out = new ArrayList<>();
            scala.collection.Iterator<?> it = sc.iterator();
            while (it.hasNext()) out.add(toJava(it.next()));
            return out;
        }
        if (value instanceof java.util.Map<?, ?> jm) {
            Map<Object, Object> out = new LinkedHashMap<>(jm.size());
            for (Map.Entry<?, ?> e : jm.entrySet()) {
                out.put(toJava(e.getKey()), toJava(e.getValue()));
            }
            return out;
        }
        if (value instanceof java.util.List<?> jl) {
            List<Object> out = new ArrayList<>(jl.size());
            for (Object e : jl) out.add(toJava(e));
            return out;
        }
        return value;
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
