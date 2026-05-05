package com.pods.agent.dmn;

import java.util.Map;

public record MatchedRow(int rowIndex, String ruleId, Map<String, Object> outputs) {
}
