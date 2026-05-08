package com.pods.agent.service.expression;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ExpressionValidator {
    private final BooleanExpressionEvaluator evaluator;

    public ExpressionValidator(BooleanExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public ValidationResult validate(String expression) {
        return validate(expression, Map.of());
    }

    public ValidationResult validate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return new ValidationResult(false, "expression is required");
        }
        try {
            evaluator.eval(expression, context == null ? Map.of() : context);
            return new ValidationResult(true, null);
        } catch (ExpressionValidationException e) {
            return new ValidationResult(false, e.getMessage());
        } catch (Exception e) {
            return new ValidationResult(false, "invalid expression");
        }
    }

    public record ValidationResult(boolean valid, String error) {}
}
