package com.pods.agent.service;

import com.pods.agent.domain.Memory;
import com.pods.agent.repository.MemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    @Test
    void createsMemoryAndSyncsBlob() {
        MemoryRepository repo = mock(MemoryRepository.class);
        SkillFileStorageService storage = mock(SkillFileStorageService.class);
        when(repo.findByUserIdAndFilePath("u1", "project.md")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByUserId("u1")).thenReturn(List.of(
                Memory.builder().id("m1").userId("u1").category("project").memoryFilePath("project.md").content("abc").build()
        ));
        doNothing().when(storage).put(any(), any(), any());
        MemoryService service = new MemoryService(repo, storage);

        Memory memory = service.saveOrUpdateMemory("u1", "s1", "project", "project.md", "content", List.of("tag1"));

        assertTrue(memory.getMemoryFilePath().equals("project.md"));
        assertTrue(service.renderMemoryIndexMarkdown("u1").contains("project.md"));
    }

    @Test
    void deleteReturnsFalseWhenPathMissing() {
        MemoryRepository repo = mock(MemoryRepository.class);
        SkillFileStorageService storage = mock(SkillFileStorageService.class);
        when(repo.findByUserIdAndFilePath("u1", "missing.md")).thenReturn(Optional.empty());
        MemoryService service = new MemoryService(repo, storage);

        boolean deleted = service.deleteMemoryByPath("u1", "missing.md");

        assertFalse(deleted);
    }
}
