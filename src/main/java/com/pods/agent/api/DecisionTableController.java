package com.pods.agent.api;

import com.pods.agent.api.dto.DecisionTableDtos;
import com.pods.agent.domain.DecisionTable;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.service.DecisionTableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/decision-tables", "/api/v1/order-validation/decision-tables"})
@Tag(name = "Decision Tables", description = "Decision table CRUD and evaluation APIs")
public class DecisionTableController {
    private final DecisionTableService decisionTableService;

    public DecisionTableController(DecisionTableService decisionTableService) {
        this.decisionTableService = decisionTableService;
    }

    @GetMapping
    @Operation(summary = "List decision tables")
    public ResponseEntity<?> list() {
        List<DecisionTableDtos.DecisionTableSummary> rows = decisionTableService.list().stream().map(table -> {
            DecisionTableDtos.DecisionTableSummary dto = new DecisionTableDtos.DecisionTableSummary();
            dto.setName(table.getName());
            dto.setDescription(table.getDescription());
            dto.setHitPolicy(table.getHitPolicy());
            dto.setUpdatedAt(table.getUpdatedAt());
            return dto;
        }).toList();
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{name}")
    @Operation(summary = "Get decision table by name")
    public ResponseEntity<?> get(@PathVariable String name) {
        try {
            return ResponseEntity.ok(toDetail(decisionTableService.getByName(name)));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "Create decision table")
    public ResponseEntity<?> create(@RequestBody DecisionTableDtos.DecisionTableUpsertRequest request) {
        try {
            DecisionTable created = decisionTableService.create(fromRequest(request));
            return ResponseEntity.ok(toDetail(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PutMapping("/{name}")
    @Operation(summary = "Update decision table by name")
    public ResponseEntity<?> update(@PathVariable String name,
                                    @RequestBody DecisionTableDtos.DecisionTableUpsertRequest request) {
        try {
            DecisionTable updated = decisionTableService.upsert(name, fromRequest(request));
            return ResponseEntity.ok(toDetail(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Delete decision table by name")
    public ResponseEntity<?> delete(@PathVariable String name) {
        decisionTableService.delete(name);
        return ResponseEntity.ok(Map.of("deleted", true, "name", name));
    }

    @PostMapping("/{name}/evaluate")
    @Operation(summary = "Evaluate decision table against inputs")
    public ResponseEntity<?> evaluate(@PathVariable String name,
                                      @RequestBody(required = false) DecisionTableDtos.EvaluateDecisionTableRequest request) {
        try {
            Map<String, Object> inputs = request == null || request.getInputs() == null ? Map.of() : request.getInputs();
            var result = decisionTableService.evaluate(name, inputs);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tableName", name);
            response.putAll(result.asMap());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    private DecisionTable fromRequest(DecisionTableDtos.DecisionTableUpsertRequest request) {
        return DecisionTable.builder()
                .name(request.getName())
                .description(request.getDescription())
                .hitPolicy(request.getHitPolicy())
                .dmnJson(decisionTableService.toJson(request.getDmnJson()))
                .metadataJson(decisionTableService.toJson(request.getMetadata()))
                .build();
    }

    private DecisionTableDtos.DecisionTableDetail toDetail(DecisionTable table) {
        DecisionTableDtos.DecisionTableDetail dto = new DecisionTableDtos.DecisionTableDetail();
        dto.setId(table.getId());
        dto.setName(table.getName());
        dto.setDescription(table.getDescription());
        dto.setHitPolicy(table.getHitPolicy());
        dto.setDmnJson(decisionTableService.parseJsonSafely(table.getDmnJson()));
        dto.setMetadata(decisionTableService.parseJsonSafely(table.getMetadataJson()));
        dto.setCreatedAt(table.getCreatedAt());
        dto.setUpdatedAt(table.getUpdatedAt());
        return dto;
    }
}
