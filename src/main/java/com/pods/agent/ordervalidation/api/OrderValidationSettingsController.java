package com.pods.agent.ordervalidation.api;

import com.pods.agent.domain.Skill;
import com.pods.agent.ordervalidation.model.OrderValidationUiSettings;
import com.pods.agent.ordervalidation.repository.OrderValidationSettingsRepository;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.service.DecisionTableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the singleton {@code agent.order_validation_settings} row.
 * Skill + rule-domain scope are derived from the chosen workflow on
 * the server (see {@link com.pods.agent.ordervalidation.service.OvScopeService});
 * only decision-table allow-list is user-managed here.
 */
@RestController
@RequestMapping("/api/v1/order-validation")
@Slf4j
public class OrderValidationSettingsController {

    private final OrderValidationSettingsRepository settingsRepo;
    private final SkillRepository skillRepository;
    private final DecisionTableService decisionTableService;

    public OrderValidationSettingsController(OrderValidationSettingsRepository settingsRepo,
                                             SkillRepository skillRepository,
                                             DecisionTableService decisionTableService) {
        this.settingsRepo = settingsRepo;
        this.skillRepository = skillRepository;
        this.decisionTableService = decisionTableService;
    }

    @GetMapping("/settings")
    public OrderValidationUiSettings get() {
        return settingsRepo.load();
    }

    @PutMapping("/settings")
    public OrderValidationUiSettings update(@RequestBody OrderValidationUiSettings body) {
        return settingsRepo.save(body);
    }

    /**
     * Lists every enabled skill EXCEPT the one currently selected as
     * the workflow — the workflow's skill is implicit in scope, so the
     * picker only surfaces optional extras the user can add or remove.
     */
    @GetMapping("/scope/skills")
    public List<Map<String, Object>> scopeSkills() {
        String workflowId = settingsRepo.load().workflowId();
        return skillRepository.findAll().stream()
                .filter(Skill::isEnabled)
                .filter(s -> workflowId == null || !workflowId.equals(s.getId()))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("description", s.getDescription());
                    return m;
                })
                .toList();
    }

    /** Lists every decision table name available to the scoped assistant. */
    @GetMapping("/scope/decision-tables")
    public List<Map<String, Object>> scopeDecisionTables() {
        return decisionTableService.list().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.getName());
                    m.put("description", t.getDescription());
                    return m;
                })
                .toList();
    }
}
