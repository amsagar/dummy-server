package com.pods.agent.service;

import com.pods.agent.dmn.DmnDecisionTable;
import com.pods.agent.dmn.EvaluationResult;
import com.pods.agent.domain.DecisionTable;
import com.pods.agent.repository.DecisionTableRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DecisionTableService {
    private final DecisionTableRepository repository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, DmnDecisionTable> cache = new ConcurrentHashMap<>();

    public DecisionTableService(DecisionTableRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<DecisionTable> list() {
        return repository.findAll();
    }

    public DecisionTable getByName(String name) {
        return repository.findByName(name).orElseThrow(() -> new IllegalArgumentException("Decision table not found: " + name));
    }

    public DecisionTable create(DecisionTable table) {
        validate(table);
        if (repository.findByName(table.getName()).isPresent()) {
            throw new IllegalArgumentException("Decision table already exists: " + table.getName());
        }
        DecisionTable saved = repository.save(table);
        cache.remove(saved.getName());
        return saved;
    }

    public DecisionTable upsert(String name, DecisionTable update) {
        validate(update);
        DecisionTable existing = getByName(name);
        update.setId(existing.getId());
        update.setCreatedAt(existing.getCreatedAt());
        DecisionTable saved = repository.updateByName(name, update);
        cache.remove(name);
        if (!name.equals(saved.getName())) {
            cache.remove(saved.getName());
        }
        return saved;
    }

    public void delete(String name) {
        repository.deleteByName(name);
        cache.remove(name);
    }

    public EvaluationResult evaluate(String name, Map<String, Object> inputs) {
        DecisionTable table = getByName(name);
        DmnDecisionTable parsed = cache.computeIfAbsent(name, key -> DmnDecisionTable.fromJsonString(table.getName(), table.getDmnJson()));
        return parsed.evaluate(inputs == null ? Map.of() : inputs);
    }

    public Object parseJsonSafely(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void validate(DecisionTable table) {
        if (table == null || table.getName() == null || table.getName().isBlank()) {
            throw new IllegalArgumentException("Decision table name is required");
        }
        if (table.getDmnJson() == null || table.getDmnJson().isBlank()) {
            throw new IllegalArgumentException("Decision table JSON is required");
        }
        DmnDecisionTable.fromJsonString(table.getName(), table.getDmnJson());
    }
}
