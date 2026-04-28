package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.Skill;
import com.pods.agent.service.SkillRegistryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillToolCallbackTest {

    @Test
    void call_loadsSkillAndReturnsSkillContentBlock() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SseEventSender sender = mock(SseEventSender.class);
        RuntimeTuningProperties props = new RuntimeTuningProperties();

        Skill skill = Skill.builder()
                .id("system-validation")
                .name("Validation Report Format")
                .description("Validation output structure")
                .enabled(true)
                .build();
        SkillRegistryService.SkillSnapshot snapshot = new SkillRegistryService.SkillSnapshot(
                skill,
                Map.of(
                        "SKILL.md", "# Validation Report Format\nUse numbered sections only.",
                        "examples.md", "Example output"
                )
        );
        when(registry.getEnabledSkills()).thenReturn(List.of(snapshot));
        when(registry.getEnabledSkillByName("Validation Report Format")).thenReturn(snapshot);

        SkillToolCallback callback = new SkillToolCallback(
                registry,
                props,
                sender,
                "s1",
                "t1",
                new tools.jackson.databind.ObjectMapper(),
                null,
                null
        );

        String output = callback.call("{\"name\":\"Validation Report Format\"}");

        assertTrue(output.contains("<skill_content name=\"Validation Report Format\">"));
        assertTrue(output.contains("# Skill: Validation Report Format"));
        assertTrue(output.contains("Use numbered sections only."));
        assertTrue(output.contains("Base directory for this skill: workspace://skills/validation_report_format/"));
        assertTrue(output.contains("<skill_files>"));
        assertTrue(output.contains("<file>workspace://skills/validation_report_format/examples.md</file>"));
        verify(sender).sendToolCall(anyString(), anyString(), anyString(), any());
        verify(sender).sendToolResult(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void definition_mentionsAvailableSkills() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SseEventSender sender = mock(SseEventSender.class);
        RuntimeTuningProperties props = new RuntimeTuningProperties();
        Skill skill = Skill.builder().id("s1").name("Billing Rules").description("Billing checks").enabled(true).build();
        when(registry.getEnabledSkills()).thenReturn(List.of(
                new SkillRegistryService.SkillSnapshot(skill, Map.of("SKILL.md", "content"))
        ));

        SkillToolCallback callback = new SkillToolCallback(
                registry,
                props,
                sender,
                "s1",
                "t1",
                new tools.jackson.databind.ObjectMapper(),
                null,
                null
        );

        String description = callback.getToolDefinition().description();
        assertTrue(description.contains("<available_skills>"));
        assertTrue(description.contains("Billing Rules"));
    }

    @Test
    void call_canLoadMultipleSkillsInSameTurn() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SseEventSender sender = mock(SseEventSender.class);
        RuntimeTuningProperties props = new RuntimeTuningProperties();
        Skill skillA = Skill.builder().id("s1").name("Billing Rules").description("Billing checks").enabled(true).build();
        Skill skillB = Skill.builder().id("s2").name("Timestamp Rules").description("Timestamp checks").enabled(true).build();
        SkillRegistryService.SkillSnapshot snapshotA = new SkillRegistryService.SkillSnapshot(
                skillA,
                Map.of("SKILL.md", "billing content")
        );
        SkillRegistryService.SkillSnapshot snapshotB = new SkillRegistryService.SkillSnapshot(
                skillB,
                Map.of("SKILL.md", "timestamp content")
        );
        when(registry.getEnabledSkills()).thenReturn(List.of(snapshotA, snapshotB));
        when(registry.getEnabledSkillByName("Billing Rules")).thenReturn(snapshotA);
        when(registry.getEnabledSkillByName("Timestamp Rules")).thenReturn(snapshotB);

        SkillToolCallback callback = new SkillToolCallback(
                registry,
                props,
                sender,
                "s1",
                "t1",
                new tools.jackson.databind.ObjectMapper(),
                null,
                null
        );

        String first = callback.call("{\"name\":\"Billing Rules\"}");
        String second = callback.call("{\"name\":\"Timestamp Rules\"}");

        assertTrue(first.contains("<skill_content name=\"Billing Rules\">"));
        assertTrue(second.contains("<skill_content name=\"Timestamp Rules\">"));
    }
}
