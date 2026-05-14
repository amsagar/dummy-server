package com.pods.agent.ruledomain.runtime;

import org.camunda.feel.FeelEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scala.util.Either;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-0 spike. Verifies that the Camunda FEEL engine handles every
 * expression class used by the hand-authored pods-order-validation BPMN.
 * If any of these assertions fail, the plan must switch to a different
 * FEEL implementation before further phases are built.
 *
 * These tests intentionally don't boot the full Spring context — they
 * exercise the engine directly so failures are unambiguous.
 */
@DisplayName("FEEL engine — Phase 0 spike")
class FeelEngineSpikeTest {

    private static FeelEngine engine;

    @BeforeAll
    static void boot() {
        engine = new FeelEngine.Builder().build();
    }

    @Test
    @DisplayName("property navigation: order.OrderIdentity")
    void propertyNav() {
        Object v = eval("order.OrderIdentity",
                Map.of("order", Map.of("OrderIdentity", 600030447L)));
        assertEquals(600030447L, ((Number) v).longValue());
    }

    @Test
    @DisplayName("list filter with membership: list contains([...], ItemCode)")
    void listMembershipFilter() {
        var order = Map.of("Lines", List.of(
                Map.of("ItemCode", "IDEL", "Sequence", 1),
                Map.of("ItemCode", "WTW",  "Sequence", 2),
                Map.of("ItemCode", "RETSC","Sequence", 3),
                Map.of("ItemCode", "DUMMY","Sequence", 4)
        ));
        Object v = eval(
                "order.Lines[list contains([\"IDEL\",\"RETSC\",\"LDT\",\"REDEL\",\"FPU\"], ItemCode)]",
                Map.of("order", order));
        assertTrue(v instanceof List);
        assertEquals(2, ((List<?>) v).size());
    }

    @Test
    @DisplayName("array index with predicate: leg.Addresses[AddressType=\"X\"][1].PostalCode")
    void filterThenIndex() {
        var leg = Map.of("Addresses", List.of(
                Map.of("AddressType", "Origination", "PostalCode", "30301"),
                Map.of("AddressType", "Destination", "PostalCode", "94105")
        ));
        Object v = eval(
                "leg.Addresses[AddressType = \"Origination\"][1].PostalCode",
                Map.of("leg", leg));
        assertEquals("30301", v);
    }

    @Test
    @DisplayName("string contains: contains(StatusCode, \"OutOfMarket\")")
    void stringContains() {
        Object yes = eval("contains(s, \"OutOfMarket\")",
                Map.of("s", "OutOfMarket-Origination"));
        Object no = eval("contains(s, \"OutOfMarket\")",
                Map.of("s", "DualCityService"));
        assertEquals(true, yes);
        assertEquals(false, no);
    }

    @Test
    @DisplayName("if-then-else with null check")
    void conditional() {
        Object v = eval(
                "if l.ServiceCode != null and l.ServiceCode != \"\" then l.ServiceCode else \"NEW\"",
                Map.of("l", Map.of("ServiceCode", "")));
        assertEquals("NEW", v);
    }

    @Test
    @DisplayName("for-in comprehension with sort")
    void forInWithSort() {
        var legs = List.of(
                Map.of("ItemCode", "REDEL", "Sequence", 3),
                Map.of("ItemCode", "IDEL",  "Sequence", 1),
                Map.of("ItemCode", "WTW",   "Sequence", 2)
        );
        Object v = eval(
                "for l in sort(legs, function(a,b) a.Sequence < b.Sequence) return l.ItemCode",
                Map.of("legs", legs));
        assertEquals(List.of("IDEL", "WTW", "REDEL"), v);
    }

    @Test
    @DisplayName("count() on a filtered list")
    void countFiltered() {
        var legs = List.of(
                Map.of("Addresses", List.of(Map.of("AddressType", "Origination"))),
                Map.of("Addresses", List.of(Map.of("AddressType", "Destination")))
        );
        Object first = eval("count(leg.Addresses[AddressType = \"Origination\"]) = 0",
                Map.of("leg", legs.get(0)));
        Object second = eval("count(leg.Addresses[AddressType = \"Origination\"]) = 0",
                Map.of("leg", legs.get(1)));
        assertEquals(false, first);
        assertEquals(true, second);
    }

    @Test
    @DisplayName("inline map literal as service-code mapping")
    void mapLookup() {
        Object v = eval(
                "{IDEL:\"NEW\",RETSC:\"WRT\",LDT:\"WTW\"}[code]",
                Map.of("code", "LDT"));
        assertEquals("WTW", v);
    }

    @SuppressWarnings("unchecked")
    private static Object eval(String expr, Map<String, Object> ctx) {
        Either<FeelEngine.Failure, Object> r = engine.evalExpression(expr, ctx);
        if (r.isRight()) return r.right().get();
        fail("FEEL eval failed: " + r.left().get().message() + " for: " + expr);
        return null;
    }
}
