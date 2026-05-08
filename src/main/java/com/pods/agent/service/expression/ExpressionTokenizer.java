package com.pods.agent.service.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExpressionTokenizer {
    public List<Token> tokenize(String expression) {
        if (expression == null) return List.of(new Token(TokenType.EOF, "", 0));
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(", i++)); continue; }
            if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")", i++)); continue; }
            if (c == ',') { tokens.add(new Token(TokenType.COMMA, ",", i++)); continue; }
            if (c == '!') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.OP_NE, "!=", i)); i += 2;
                } else {
                    tokens.add(new Token(TokenType.OP_NOT, "!", i++));
                }
                continue;
            }
            if (c == '=') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.OP_EQ, "==", i)); i += 2; continue;
                }
                throw new ExpressionValidationException("expected == at col " + (i + 1));
            }
            if (c == '<') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.OP_LE, "<=", i)); i += 2;
                } else {
                    tokens.add(new Token(TokenType.OP_LT, "<", i++));
                }
                continue;
            }
            if (c == '>') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.OP_GE, ">=", i)); i += 2;
                } else {
                    tokens.add(new Token(TokenType.OP_GT, ">", i++));
                }
                continue;
            }
            if (c == '&' && i + 1 < expression.length() && expression.charAt(i + 1) == '&') {
                tokens.add(new Token(TokenType.OP_AND, "&&", i)); i += 2; continue;
            }
            if (c == '|' && i + 1 < expression.length() && expression.charAt(i + 1) == '|') {
                tokens.add(new Token(TokenType.OP_OR, "||", i)); i += 2; continue;
            }
            if (c == '"' || c == '\'') {
                int start = i;
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean escaped = false;
                while (i < expression.length()) {
                    char ch = expression.charAt(i++);
                    if (escaped) {
                        sb.append(ch);
                        escaped = false;
                    } else if (ch == '\\') {
                        escaped = true;
                    } else if (ch == quote) {
                        tokens.add(new Token(TokenType.STRING, sb.toString(), start));
                        break;
                    } else {
                        sb.append(ch);
                    }
                }
                if (tokens.isEmpty() || tokens.get(tokens.size() - 1).pos != start) {
                    throw new ExpressionValidationException("unterminated string at col " + (start + 1));
                }
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < expression.length() && Character.isDigit(expression.charAt(i + 1)))) {
                int start = i++;
                while (i < expression.length()) {
                    char ch = expression.charAt(i);
                    if (!Character.isDigit(ch) && ch != '.') break;
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, expression.substring(start, i), start));
                continue;
            }
            if (c == '$') {
                int start = i++;
                while (i < expression.length()) {
                    char ch = expression.charAt(i);
                    if (Character.isWhitespace(ch) || ch == ')' || ch == '(' || ch == ',' || ch == '&' || ch == '|' || ch == '!' || ch == '<' || ch == '>' || ch == '=') break;
                    i++;
                }
                tokens.add(new Token(TokenType.PATH, expression.substring(start, i), start));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < expression.length()) {
                    char ch = expression.charAt(i);
                    if (!Character.isLetterOrDigit(ch) && ch != '_') break;
                    i++;
                }
                String text = expression.substring(start, i);
                String upper = text.toUpperCase(Locale.ROOT);
                if ("IN".equals(upper)) tokens.add(new Token(TokenType.OP_IN, text, start));
                else if ("CONTAINS".equals(upper)) tokens.add(new Token(TokenType.OP_CONTAINS, text, start));
                else if ("TRUE".equals(upper) || "FALSE".equals(upper)) tokens.add(new Token(TokenType.BOOLEAN, text.toLowerCase(Locale.ROOT), start));
                else if ("NULL".equals(upper)) tokens.add(new Token(TokenType.NULL, "null", start));
                else tokens.add(new Token(TokenType.IDENTIFIER, text, start));
                continue;
            }
            throw new ExpressionValidationException("unexpected token '" + c + "' at col " + (i + 1));
        }
        tokens.add(new Token(TokenType.EOF, "", expression.length()));
        return tokens;
    }

    public enum TokenType {
        LPAREN, RPAREN, COMMA,
        OP_OR, OP_AND, OP_NOT,
        OP_EQ, OP_NE, OP_LT, OP_LE, OP_GT, OP_GE, OP_IN, OP_CONTAINS,
        PATH, IDENTIFIER, STRING, NUMBER, BOOLEAN, NULL,
        EOF
    }

    public record Token(TokenType type, String text, int pos) {}
}
