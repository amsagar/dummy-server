package com.pods.agent.dmn;

import java.util.List;
import java.util.Map;

public record EvaluationResult(boolean matched, List<MatchedRow> matchedRows, Map<String, Object> outputs) {
    public Map<String, Object> asMap() {
        return Map.of(
                "matched", matched,
                "matchedRows", matchedRows == null ? List.of() : matchedRows,
                "outputs", outputs == null ? Map.of() : outputs
        );
    }
}
