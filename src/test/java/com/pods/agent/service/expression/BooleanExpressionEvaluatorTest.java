package com.pods.agent.service.expression;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BooleanExpressionEvaluatorTest {

    private final BooleanExpressionEvaluator evaluator = new BooleanExpressionEvaluator();

    @Test
    void evaluatesComparisonsAndBooleanOperators() {
        Map<String, Object> context = Map.of(
                "input", Map.of("amount", 150, "country", "US"),
                "flags", Map.of("enabled", true)
        );
        assertTrue(evaluator.eval("$.input.amount > 100 && $.input.country == \"US\"", context));
        assertTrue(evaluator.eval("$.flags.enabled == true || $.input.amount < 10", context));
        assertFalse(evaluator.eval("$.input.amount < 100 || $.input.country == \"CA\"", context));
    }

    @Test
    void supportsInAndContains() {
        Map<String, Object> context = Map.of(
                "input", Map.of("country", "US", "text", "hello world"),
                "items", List.of("a", "b", "c")
        );
        assertTrue(evaluator.eval("$.input.country IN (\"US\", \"UK\")", context));
        assertTrue(evaluator.eval("$.input.text CONTAINS \"world\"", context));
        assertTrue(evaluator.eval("$.items CONTAINS \"b\"", context));
    }

    @Test
    void validatesUnknownIdentifiersAndSyntax() {
        assertThrows(ExpressionValidationException.class, () -> evaluator.eval("foo == 1", Map.of()));
        assertThrows(ExpressionValidationException.class, () -> evaluator.eval("($.a == 1", Map.of()));
    }
}
