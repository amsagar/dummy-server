package com.pods.agent.service;

import com.pods.agent.service.expression.BooleanExpressionEvaluator;
import com.pods.agent.service.expression.PathResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArgMappingResolverTest {

    private final ArgMappingResolver resolver = new ArgMappingResolver(new BooleanExpressionEvaluator());

    @Test
    void resolvesSystemFunctionCall() {
        Map<String, Object> context = Map.of("input", Map.of("name", "alice"));
        Object value = resolver.resolveOne("upper($.input.name)", context, key -> PathResolver.resolvePath(context, key));
        assertEquals("ALICE", value);
    }

    @Test
    void resolvesConditionalMapping() {
        Map<String, Object> context = Map.of("input", Map.of("country", "US"));
        Object value = resolver.resolveOne(
                "#if($.input.country == \"US\") \"USD\" #elseIf($.input.country == \"UK\") \"GBP\" #else \"EUR\" #endif",
                context,
                key -> PathResolver.resolvePath(context, key)
        );
        assertEquals("USD", value);
    }

    @Test
    void pathResolverSupportsArrayPredicates() {
        Map<String, Object> context = Map.of(
                "records",
                List.of(
                        Map.of("status", "inactive", "id", "1"),
                        Map.of("status", "active", "id", "2")
                )
        );
        Object activeId = PathResolver.resolvePath(context, "$.records[status='active'][0].id", true);
        assertEquals("2", activeId);
        assertNull(PathResolver.resolvePath(context, "$.records[status='missing'][0].id", true));
    }
}
