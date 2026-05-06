package com.pods.agent.service;

import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.ToolChainRepository;
import com.pods.agent.repository.ToolChainVersionRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolChainServiceTest {

    @Test
    void approveSystemToolChainMarksApprovedOnce() {
        ToolChainRepository chainRepository = mock(ToolChainRepository.class);
        ToolChainVersionRepository versionRepository = mock(ToolChainVersionRepository.class);
        ToolChainService service = new ToolChainService(chainRepository, versionRepository, new ObjectMapper());

        ToolChain pending = ToolChain.builder()
                .id("tc-1")
                .name("Suggested flow")
                .origin(ToolChainService.ORIGIN_SYSTEM_SUGGESTED)
                .approvalStatus(ToolChainService.APPROVAL_PENDING)
                .metadataJson("{}")
                .build();
        when(chainRepository.findById("tc-1")).thenReturn(Optional.of(pending));
        when(chainRepository.update(any(ToolChain.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ToolChain approved = service.approveSystemToolChain("tc-1", "approver-1", "Looks good");

        assertEquals(ToolChainService.APPROVAL_APPROVED, approved.getApprovalStatus());
        assertEquals("approver-1", approved.getApprovedBy());
    }
}
