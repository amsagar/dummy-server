package com.pods.agent.workflow.api;

import com.pods.agent.workflow.migration.ToolChainWorkflowMigrationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cutover migration endpoints used for full ToolChain -> Workflow replacement.
 */
@RestController
@RequestMapping("/api/v1/workflow/migration")
public class WorkflowMigrationController {

    private final ToolChainWorkflowMigrationService migrationService;

    public WorkflowMigrationController(ToolChainWorkflowMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping("/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun() {
        return ResponseEntity.ok(migrationService.dryRun());
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        return ResponseEntity.ok(migrationService.migrateAll());
    }
}
