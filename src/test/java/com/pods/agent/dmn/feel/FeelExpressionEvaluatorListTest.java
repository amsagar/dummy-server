package com.pods.agent.dmn.feel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * List-aware matching cases for {@link FeelExpressionEvaluator}. Pinned to
 * the regression that motivated them: the "Leg Sequences" decision table
 * evaluates with a {@code java.util.ArrayList} input column against a
 * literal cell like {@code ["NEW","WTW","RDL"]}; pre-fix the matcher
 * fell back to {@code String.valueOf(actual)} which produced
 * {@code [NEW, WTW, RDL]} and never equalled the rule cell.
 */
class FeelExpressionEvaluatorListTest {

    // ── ordered list-vs-list equality ───────────────────────────────

    @Test
    void listLiteralMatchesIdenticalActualList() {
        assertTrue(FeelExpressionEvaluator.matches("[\"NEW\",\"WTW\",\"RDL\"]",
                List.of("NEW", "WTW", "RDL")));
    }

    @Test
    void listLiteralIsOrderSensitive() {
        assertFalse(FeelExpressionEvaluator.matches("[\"NEW\",\"WTW\",\"RDL\"]",
                List.of("WTW", "NEW", "RDL")));
    }

    @Test
    void listLengthMismatchIsRejected() {
        assertFalse(FeelExpressionEvaluator.matches("[\"NEW\",\"WTW\"]",
                List.of("NEW", "WTW", "RDL")));
    }

    @Test
    void listElementsCompareCaseInsensitivelyAndTrimmed() {
        assertTrue(FeelExpressionEvaluator.matches("[\"new\",\"  wtw \"]",
                List.of("NEW", "WTW")));
    }

    @Test
    void singleQuotedStringsAccepted() {
        assertTrue(FeelExpressionEvaluator.matches("['NEW','WTW']",
                List.of("NEW", "WTW")));
    }

    @Test
    void emptyListLiteralMatchesEmptyActual() {
        assertTrue(FeelExpressionEvaluator.matches("[]", List.of()));
    }

    @Test
    void numericListLiteralComparesNumerically() {
        assertTrue(FeelExpressionEvaluator.matches("[1, 2, 3]", List.of(1, 2, 3)));
        assertTrue(FeelExpressionEvaluator.matches("[1.0, 2.0]", List.of(1, 2)));
    }

    // ── scalar-in-list membership ───────────────────────────────────

    @Test
    void scalarActualMatchesAnyOfListLiteral() {
        assertTrue(FeelExpressionEvaluator.matches("[\"NEW\",\"WRT\"]", "NEW"));
        assertTrue(FeelExpressionEvaluator.matches("[\"NEW\",\"WRT\"]", "WRT"));
    }

    @Test
    void scalarActualNotInListLiteralFails() {
        assertFalse(FeelExpressionEvaluator.matches("[\"NEW\",\"WRT\"]", "RDL"));
    }

    @Test
    void scalarActualMembershipIsCaseInsensitive() {
        assertTrue(FeelExpressionEvaluator.matches("[\"NEW\",\"WRT\"]", "new"));
    }

    // ── `list contains(<haystack>, <needle>)` rule cells ────────────

    @Test
    void listContainsTrueWhenNeedleInHaystack() {
        // actual value is irrelevant — the literal predicate is what's evaluated
        assertTrue(FeelExpressionEvaluator.matches(
                "list contains([\"NEW\",\"WTW\",\"RDL\"], \"WTW\")", "anything"));
    }

    @Test
    void listContainsFalseWhenNeedleAbsent() {
        assertFalse(FeelExpressionEvaluator.matches(
                "list contains([\"NEW\",\"WTW\"], \"RDL\")", "anything"));
    }

    // ── regression guards for existing scalar/string/number/wildcard paths ──

    @Test
    void blankExpressionStillMatchesAnything() {
        assertTrue(FeelExpressionEvaluator.matches("", "NEW"));
        assertTrue(FeelExpressionEvaluator.matches("  ", List.of("NEW")));
    }

    @Test
    void wildcardExpressionsStillMatch() {
        assertTrue(FeelExpressionEvaluator.matches("-", "anything"));
        assertTrue(FeelExpressionEvaluator.matches("*", List.of("anything")));
    }

    @Test
    void plainStringEqualityStillWorks() {
        assertTrue(FeelExpressionEvaluator.matches("\"NEW\"", "NEW"));
        assertFalse(FeelExpressionEvaluator.matches("\"NEW\"", "WRT"));
    }

    @Test
    void numericComparisonStillWorks() {
        assertTrue(FeelExpressionEvaluator.matches("> 5", 10));
        assertFalse(FeelExpressionEvaluator.matches("> 5", 3));
    }

    @Test
    void numericRangeStillWorks() {
        assertTrue(FeelExpressionEvaluator.matches("[1..10]", 5));
        assertFalse(FeelExpressionEvaluator.matches("(1..10)", 1));
    }
}
