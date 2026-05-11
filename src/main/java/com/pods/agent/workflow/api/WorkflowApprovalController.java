package com.pods.agent.workflow.api;

import com.pods.agent.workflow.engine.WorkflowManager;
import com.pods.agent.workflow.persistence.PendingApprovalRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflow/approvals")
public class WorkflowApprovalController {

    private final WorkflowApprovalService service;

    public WorkflowApprovalController(WorkflowApprovalService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<PendingApprovalRow> pending = service.listPending(limit);
        return ResponseEntity.ok(Map.of(
                "total", service.countPending(),
                "pending", pending
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PendingApprovalRow> get(@PathVariable("id") String id) {
        return service.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return decide(id, body, true);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return decide(id, body, false);
    }

    private ResponseEntity<Map<String, Object>> decide(String id,
                                                       Map<String, Object> body,
                                                       boolean approve) {
        String decidedBy = body == null ? null : asString(body.get("decidedBy"));
        String comment = body == null ? null : asString(body.get("comment"));
        Optional<WorkflowManager.StartResult> r = approve
                ? service.approve(id, decidedBy, comment)
                : service.reject(id, decidedBy, comment);
        if (r.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "ok", false,
                    "id", id,
                    "error", "approval not found or already decided"
            ));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("id", id);
        payload.put("decision", approve ? "approve" : "reject");
        payload.put("instanceId", r.get().instanceId());
        payload.put("state", r.get().state().wire());
        payload.put("error", r.get().errorMessage());
        return ResponseEntity.ok(payload);
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
