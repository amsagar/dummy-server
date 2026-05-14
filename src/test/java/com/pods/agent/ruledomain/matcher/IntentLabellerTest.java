package com.pods.agent.ruledomain.matcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentLabellerTest {

    private final IntentLabeller labeller = new IntentLabeller();

    @Test
    void collapsesNumericRunsSoSameIntentReuses() {
        String a = labeller.labelFor("pods-order-validation", "Validate order 600030447");
        String b = labeller.labelFor("pods-order-validation", "Validate order 600030500");
        // The two requests differ only in order id — they MUST yield the same label
        // so we don't accumulate a compiled domain per order id.
        assertEquals(a, b);
    }

    @Test
    void normalisesCaseAndPunctuation() {
        assertEquals("validate-order-*",
                labeller.labelFor("pods-order-validation", "VALIDATE Order 12345!!!"));
    }

    @Test
    void fallsBackToSkillNameOnEmpty() {
        assertEquals("pods-order-validation",
                labeller.labelFor("pods-order-validation", ""));
    }
}
