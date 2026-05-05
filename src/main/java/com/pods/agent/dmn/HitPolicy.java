package com.pods.agent.dmn;

public enum HitPolicy {
    FIRST,
    UNIQUE,
    COLLECT;

    public static HitPolicy from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIRST;
        }
        for (HitPolicy value : values()) {
            if (value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return FIRST;
    }
}
