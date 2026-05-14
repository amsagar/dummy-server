package com.pods.agent.ruledomain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Centralized metrics for the compiled rule-domain path. All counters and
 * timers are tagged with {@code skill} where applicable so the admin UI
 * can show per-skill breakdowns.
 *
 * MeterRegistry is optional — if no actuator is present we emit no-ops.
 */
@Component
public class RuleDomainMetrics {

    private final MeterRegistry registry;

    @Autowired
    public RuleDomainMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordMatchHit(String skillName) {
        counter("rule_domain.match.hit", skillName).increment();
    }

    public void recordMatchMiss(String skillName) {
        counter("rule_domain.match.miss", skillName).increment();
    }

    public void recordCompileSuccess(String skillName) {
        counter("rule_domain.compile.success", skillName).increment();
    }

    public void recordCompileFailure(String skillName, String reason) {
        registry.counter("rule_domain.compile.failure",
                "skill", nz(skillName), "reason", nz(reason)).increment();
    }

    public void recordExecuteSuccess(String skillName, long latencyMs) {
        counter("rule_domain.execute.success", skillName).increment();
        timer("rule_domain.execute.latency", skillName).record(Duration.ofMillis(latencyMs));
    }

    public void recordExecuteFailure(String skillName) {
        counter("rule_domain.execute.failure", skillName).increment();
    }

    public void recordFallback(String skillName) {
        counter("rule_domain.fallback.triggered", skillName).increment();
    }

    public void recordCompileLatency(String skillName, long latencyMs) {
        timer("rule_domain.compile.latency", skillName).record(Duration.ofMillis(latencyMs));
    }

    public void recordSummarizerLatency(String skillName, long latencyMs) {
        timer("rule_domain.summarize.latency", skillName).record(Duration.ofMillis(latencyMs));
    }

    private Counter counter(String name, String skillName) {
        return registry.counter(name, "skill", nz(skillName));
    }

    private Timer timer(String name, String skillName) {
        return registry.timer(name, "skill", nz(skillName));
    }

    private static String nz(String s) {
        return s == null ? "unknown" : s;
    }
}
