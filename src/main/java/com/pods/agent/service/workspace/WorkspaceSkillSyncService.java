package com.pods.agent.service.workspace;

import com.pods.agent.service.SkillRegistryService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class WorkspaceSkillSyncService {
    private final SkillRegistryService skillRegistryService;
    private final SessionWorkspaceService workspaceService;

    public WorkspaceSkillSyncService(SkillRegistryService skillRegistryService,
                                     SessionWorkspaceService workspaceService) {
        this.skillRegistryService = skillRegistryService;
        this.workspaceService = workspaceService;
    }

    public void sync(Path workspace) {
        if (workspace == null) return;
        for (SkillRegistryService.SkillSnapshot snapshot : skillRegistryService.getEnabledSkills()) {
            String skillName = slug(snapshot.skill().getName());
            for (var file : snapshot.files().entrySet()) {
                String relativePath = ".pods-agent/skills/" + skillName + "/" + file.getKey();
                workspaceService.writeText(workspace, relativePath, file.getValue());
            }
        }
    }

    private String slug(String value) {
        if (value == null || value.isBlank()) return "skill";
        return value.trim().toLowerCase().replaceAll("[^a-z0-9_\\-]+", "_");
    }
}

