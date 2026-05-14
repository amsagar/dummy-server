package com.pods.agent.ruledomain.api;

import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.RuleExecution;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST endpoints for inspecting and managing compiled rule domains.
 * Read endpoints surface the same data the UI shows; write endpoints allow
 * an operator to force-recompile or deprecate domains.
 *
 * Endpoints:
 *   GET    /api/v1/rule-domains              list (optional ?skillId=)
 *   GET    /api/v1/rule-domains/{id}         detail + BPMN XML
 *   GET    /api/v1/rule-domains/{id}/executions   last 50 runs
 *   POST   /api/v1/rule-domains/{id}/deprecate    mark DEPRECATED
 *   POST   /api/v1/rule-domains/{id}/activate     mark ACTIVE (for DRAFT/DEPRECATED)
 */
@RestController
@RequestMapping("/api/v1/rule-domains")
public class RuleDomainController {

    private final RuleDomainRepository domainRepo;
    private final RuleExecutionRepository executionRepo;

    public RuleDomainController(RuleDomainRepository domainRepo,
                                RuleExecutionRepository executionRepo) {
        this.domainRepo = domainRepo;
        this.executionRepo = executionRepo;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String skillId) {
        List<RuleDomain> rows = skillId == null || skillId.isBlank()
                ? domainRepo.listAll()
                : domainRepo.listBySkill(skillId);
        return rows.stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return domainRepo.findById(id)
                .map(d -> {
                    Map<String, Object> body = new LinkedHashMap<>(summary(d));
                    body.put("bpmnXml", d.getBpmnXml());
                    body.put("lastError", d.getLastError());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/executions")
    public List<RuleExecution> executions(@PathVariable String id,
                                          @RequestParam(defaultValue = "50") int limit) {
        return executionRepo.listForDomain(id, Math.min(Math.max(limit, 1), 200));
    }

    @PostMapping("/{id}/deprecate")
    public ResponseEntity<?> deprecate(@PathVariable String id) {
        if (domainRepo.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        domainRepo.updateStatus(id, RuleDomain.STATUS_DEPRECATED, "Manually deprecated via admin UI");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable String id) {
        if (domainRepo.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        domainRepo.updateStatus(id, RuleDomain.STATUS_ACTIVE, null);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> summary(RuleDomain d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("skillId", d.getSkillId());
        m.put("skillName", d.getSkillName());
        m.put("intentLabel", d.getIntentLabel());
        m.put("status", d.getStatus());
        m.put("version", d.getVersion());
        m.put("flowableProcKey", d.getFlowableProcKey());
        m.put("compileAttempts", d.getCompileAttempts());
        m.put("createdAt", d.getCreatedAt());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }
}
