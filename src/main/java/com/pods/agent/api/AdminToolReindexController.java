package com.pods.agent.api;

import com.pods.agent.service.ToolRegistryService;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Slf4j
@Tag(name = "Admin")
@RequestMapping("/api/v1/admin")
public class AdminToolReindexController {

    private final ToolEmbeddingIndexService toolEmbeddingIndexService;
    private final ToolRegistryService toolRegistryService;

    public AdminToolReindexController(ToolEmbeddingIndexService toolEmbeddingIndexService,
                                       ToolRegistryService toolRegistryService) {
        this.toolEmbeddingIndexService = toolEmbeddingIndexService;
        this.toolRegistryService = toolRegistryService;
    }

    @PostMapping("/tools/reindex")
    public ResponseEntity<?> reindex() {
        toolEmbeddingIndexService.reindexAll(toolRegistryService.getEnabledTools());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
