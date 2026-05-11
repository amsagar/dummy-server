/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-core/src/main/java/org/joget/apps/app/lib/ExpressionHashVariable.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. The Joget original is wrapped in a hash-variable
 * plugin lifecycle (extends DefaultHashVariablePlugin, uses AppUtil + LogUtil).
 * We strip the plugin shell and keep only the security-critical SpEL setup:
 *
 *   - registers Math.* static functions plus an isParsed() helper
 *   - blocks type lookups (NoTypeLocator)
 *   - blocks bean lookups (NoBeanResolver)
 *   - blocks constructors (NoConstructorResolver)
 *   - blocks getClass / getClassLoader (SecureMethodResolver)
 *   - blocks property reads of class / classLoader (SecurePropertyAccessor)
 *
 * Unlike Joget's silent-fail style, parse / evaluation errors here are returned
 * as a Result.failure() so the engine can route them via error transitions
 * instead of swallowing them. This is a deliberate behavior change motivated by
 * the audit's #1 finding ("silent JSONata failures").
 */
package com.pods.agent.workflow.joget.expression;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.AccessException;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.ConstructorExecutor;
import org.springframework.expression.ConstructorResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.expression.spel.support.ReflectivePropertyAccessor;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Restricted SpEL evaluator. Use this for any user- or workflow-supplied
 * expression to keep them sandboxed against bean/type/constructor access.
 */
public final class SecureSpelEvaluator {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final StandardEvaluationContext SHARED_CONTEXT = buildSharedContext();

    private SecureSpelEvaluator() {}

    /**
     * Evaluate an expression with no bound variables.
     */
    public static Result evaluate(String expression) {
        return evaluate(expression, Map.of());
    }

    /**
     * Evaluate an expression with the supplied variables bound as #name.
     * The expression is normalized to translate $func(...) into #func(...) so
     * registered functions (Math.*, isParsed) can be called with either prefix.
     */
    public static Result evaluate(String expression, Map<String, ?> variables) {
        if (expression == null) {
            return Result.failure("expression is null");
        }
        try {
            String normalized = expression.replaceAll("\\$(\\w+\\()", "#$1");

            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setTypeLocator(SHARED_CONTEXT.getTypeLocator());
            ctx.setBeanResolver(SHARED_CONTEXT.getBeanResolver());
            ctx.setConstructorResolvers(SHARED_CONTEXT.getConstructorResolvers());
            ctx.setMethodResolvers(SHARED_CONTEXT.getMethodResolvers());
            ctx.setPropertyAccessors(SHARED_CONTEXT.getPropertyAccessors());
            registerFunctions(ctx);

            for (Map.Entry<String, ?> e : variables.entrySet()) {
                ctx.setVariable(e.getKey(), e.getValue());
            }

            Expression exp = PARSER.parseExpression(normalized);
            Object value = exp.getValue(ctx);
            return Result.success(value);
        } catch (Exception e) {
            return Result.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Convenience: evaluate to a boolean. Non-Boolean / null / failed
     * evaluations return Result.failure rather than silently coercing to false.
     */
    public static Result evaluateBoolean(String expression, Map<String, ?> variables) {
        Result r = evaluate(expression, variables);
        if (!r.ok()) {
            return r;
        }
        Object v = r.value();
        if (v instanceof Boolean) {
            return r;
        }
        return Result.failure("expression did not evaluate to Boolean: " + v);
    }

    private static StandardEvaluationContext buildSharedContext() {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        registerFunctions(ctx);
        ctx.setTypeLocator(new NoTypeLocator());
        ctx.setBeanResolver(new NoBeanResolver());
        ctx.setConstructorResolvers(List.of(new NoConstructorResolver()));
        ctx.setMethodResolvers(List.of(new SecureMethodResolver()));
        ctx.setPropertyAccessors(List.of(new SecurePropertyAccessor(), new MapAccessor()));
        return ctx;
    }

    private static void registerFunctions(StandardEvaluationContext ctx) {
        try {
            ctx.registerFunction("isParsed",
                    SecureSpelEvaluator.class.getDeclaredMethod("isParsed", String.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("isParsed missing", e);
        }
        for (Method m : Math.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                ctx.registerFunction(m.getName(), m);
            }
        }
    }

    public static Boolean isParsed(String input) {
        if (input == null) {
            return Boolean.TRUE;
        }
        return !((input.startsWith("#") && input.endsWith("#"))
                || (input.startsWith("{") && input.endsWith("}")));
    }

    public record Result(boolean ok, Object value, String error) {
        public static Result success(Object value) { return new Result(true, value, null); }
        public static Result failure(String error) { return new Result(false, null, error); }
    }

    public static class NoTypeLocator implements TypeLocator {
        @Override
        public Class<?> findType(String typeName) throws EvaluationException {
            throw new EvaluationException("Access to types is forbidden");
        }
    }

    public static class SecureMethodResolver extends ReflectiveMethodResolver {
        @Override
        public MethodExecutor resolve(EvaluationContext context, Object targetObject, String name,
                                      List<org.springframework.core.convert.TypeDescriptor> argumentTypes)
                throws AccessException {
            if ("getClass".equals(name) || "getClassLoader".equals(name)) {
                throw new AccessException("Access to getClass()/getClassLoader() is forbidden");
            }
            return super.resolve(context, targetObject, name, argumentTypes);
        }
    }

    public static class SecurePropertyAccessor extends ReflectivePropertyAccessor {
        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) throws AccessException {
            if ("class".equals(name) || "classLoader".equals(name)) {
                return false;
            }
            return super.canRead(context, target, name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            if ("class".equals(name) || "classLoader".equals(name)) {
                throw new AccessException("Access to class/classLoader is forbidden");
            }
            return super.read(context, target, name);
        }
    }

    public static class NoBeanResolver implements BeanResolver {
        @Override
        public Object resolve(EvaluationContext context, String beanName) throws AccessException {
            throw new AccessException("Access to beans is forbidden");
        }
    }

    public static class NoConstructorResolver implements ConstructorResolver {
        @Override
        public ConstructorExecutor resolve(EvaluationContext context, String typeName,
                                           List<org.springframework.core.convert.TypeDescriptor> argumentTypes)
                throws AccessException {
            throw new AccessException("Access to constructors is forbidden");
        }
    }
}
