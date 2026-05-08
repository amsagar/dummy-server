package com.pods.agent.service.expression;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BooleanExpressionEvaluator {
    private final ExpressionTokenizer tokenizer = new ExpressionTokenizer();

    public boolean eval(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) return false;
        Parser parser = new Parser(tokenizer.tokenize(expression), context);
        boolean value = parser.parseExpression();
        parser.expect(ExpressionTokenizer.TokenType.EOF, "unexpected trailing tokens");
        return value;
    }

    private static final class Parser {
        private final List<ExpressionTokenizer.Token> tokens;
        private final Map<String, Object> context;
        private int index = 0;

        Parser(List<ExpressionTokenizer.Token> tokens, Map<String, Object> context) {
            this.tokens = tokens;
            this.context = context;
        }

        boolean parseExpression() {
            return parseOr();
        }

        private boolean parseOr() {
            boolean left = parseAnd();
            while (match(ExpressionTokenizer.TokenType.OP_OR)) {
                if (left) {
                    skipBooleanExpression();
                } else {
                    left = parseAnd();
                }
            }
            return left;
        }

        private boolean parseAnd() {
            boolean left = parseUnary();
            while (match(ExpressionTokenizer.TokenType.OP_AND)) {
                if (!left) {
                    skipBooleanExpression();
                } else {
                    left = parseUnary();
                }
            }
            return left;
        }

        private boolean parseUnary() {
            if (match(ExpressionTokenizer.TokenType.OP_NOT)) return !parseUnary();
            Object left = parseOperand();
            ExpressionTokenizer.Token op = peek();
            if (op.type() == ExpressionTokenizer.TokenType.OP_EQ
                    || op.type() == ExpressionTokenizer.TokenType.OP_NE
                    || op.type() == ExpressionTokenizer.TokenType.OP_LT
                    || op.type() == ExpressionTokenizer.TokenType.OP_LE
                    || op.type() == ExpressionTokenizer.TokenType.OP_GT
                    || op.type() == ExpressionTokenizer.TokenType.OP_GE
                    || op.type() == ExpressionTokenizer.TokenType.OP_IN
                    || op.type() == ExpressionTokenizer.TokenType.OP_CONTAINS) {
                consume();
                Object right = parseRightOperand(op.type());
                return applyComparison(left, right, op.type());
            }
            return toBoolean(left);
        }

        private void skipBooleanExpression() {
            parseUnary();
        }

        private Object parseRightOperand(ExpressionTokenizer.TokenType op) {
            if (op == ExpressionTokenizer.TokenType.OP_IN && match(ExpressionTokenizer.TokenType.LPAREN)) {
                List<Object> values = new ArrayList<>();
                if (!check(ExpressionTokenizer.TokenType.RPAREN)) {
                    values.add(parseOperand());
                    while (match(ExpressionTokenizer.TokenType.COMMA)) {
                        values.add(parseOperand());
                    }
                }
                expect(ExpressionTokenizer.TokenType.RPAREN, "missing ')' for IN list");
                return values;
            }
            return parseOperand();
        }

        private Object parseOperand() {
            ExpressionTokenizer.Token token = peek();
            if (match(ExpressionTokenizer.TokenType.LPAREN)) {
                boolean nested = parseExpression();
                expect(ExpressionTokenizer.TokenType.RPAREN, "unbalanced parens at col " + (token.pos() + 1));
                return nested;
            }
            if (match(ExpressionTokenizer.TokenType.STRING)) return token.text();
            if (match(ExpressionTokenizer.TokenType.NUMBER)) return parseNumber(token);
            if (match(ExpressionTokenizer.TokenType.BOOLEAN)) return Boolean.parseBoolean(token.text());
            if (match(ExpressionTokenizer.TokenType.NULL)) return null;
            if (match(ExpressionTokenizer.TokenType.PATH)) return PathResolver.resolvePath(context, token.text());
            if (match(ExpressionTokenizer.TokenType.IDENTIFIER)) {
                throw new ExpressionValidationException("unknown identifier '" + token.text() + "' at col " + (token.pos() + 1));
            }
            throw new ExpressionValidationException("unexpected token at col " + (token.pos() + 1));
        }

        private BigDecimal parseNumber(ExpressionTokenizer.Token token) {
            try {
                return new BigDecimal(token.text());
            } catch (Exception e) {
                throw new ExpressionValidationException("invalid number '" + token.text() + "' at col " + (token.pos() + 1));
            }
        }

        private boolean applyComparison(Object left, Object right, ExpressionTokenizer.TokenType op) {
            if (op == ExpressionTokenizer.TokenType.OP_IN) {
                if (right instanceof Collection<?> list) {
                    for (Object candidate : list) {
                        if (compareEquals(left, candidate)) return true;
                    }
                    return false;
                }
                return compareEquals(left, right);
            }
            if (op == ExpressionTokenizer.TokenType.OP_CONTAINS) {
                if (left instanceof Collection<?> list) return list.stream().anyMatch(v -> compareEquals(v, right));
                String l = left == null ? "" : String.valueOf(left);
                String r = right == null ? "" : String.valueOf(right);
                return l.contains(r);
            }
            if (op == ExpressionTokenizer.TokenType.OP_EQ) return compareEquals(left, right);
            if (op == ExpressionTokenizer.TokenType.OP_NE) return !compareEquals(left, right);
            if (left == null || right == null) return false;
            ComparablePair pair = comparablePair(left, right);
            int cmp = pair.compare();
            return switch (op) {
                case OP_LT -> cmp < 0;
                case OP_LE -> cmp <= 0;
                case OP_GT -> cmp > 0;
                case OP_GE -> cmp >= 0;
                default -> false;
            };
        }

        private boolean compareEquals(Object left, Object right) {
            if (left == null || right == null) return left == null && right == null;
            ComparablePair pair = comparablePair(left, right);
            return pair.compare() == 0;
        }

        private ComparablePair comparablePair(Object left, Object right) {
            if (left instanceof Number || right instanceof Number) {
                BigDecimal a = asNumber(left);
                BigDecimal b = asNumber(right);
                if (a != null && b != null) return new ComparablePair(a, b);
            }
            return new ComparablePair(String.valueOf(left), String.valueOf(right));
        }

        private BigDecimal asNumber(Object value) {
            if (value == null) return null;
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number n) return new BigDecimal(String.valueOf(n));
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (Exception e) {
                return null;
            }
        }

        private boolean toBoolean(Object value) {
            if (value == null) return false;
            if (value instanceof Boolean b) return b;
            if (value instanceof Number n) return n.doubleValue() != 0;
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) return false;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
            return true;
        }

        private boolean match(ExpressionTokenizer.TokenType type) {
            if (!check(type)) return false;
            consume();
            return true;
        }

        private boolean check(ExpressionTokenizer.TokenType type) {
            return peek().type() == type;
        }

        private ExpressionTokenizer.Token consume() {
            return tokens.get(index++);
        }

        private ExpressionTokenizer.Token peek() {
            return tokens.get(index);
        }

        private void expect(ExpressionTokenizer.TokenType type, String message) {
            if (!match(type)) throw new ExpressionValidationException(message);
        }
    }

    private record ComparablePair(Object left, Object right) {
        int compare() {
            if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b);
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
    }
}
