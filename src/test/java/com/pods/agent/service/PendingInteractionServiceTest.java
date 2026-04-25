package com.pods.agent.service;

import com.pods.agent.domain.HitlInteraction;
import com.pods.agent.repository.HitlInteractionRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingInteractionServiceTest {

    @Test
    void createsAndResolvesPersistedInteraction() {
        HitlInteractionRepository repo = mock(HitlInteractionRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.of(
                HitlInteraction.builder().id("r").status("pending").build()
        ));
        PendingInteractionService service = new PendingInteractionService(repo, new ObjectMapper());

        String requestId = service.create("s1", "t1", "approval_required", "Approve?");
        verify(repo).save(any(HitlInteraction.class));

        service.reply(requestId, "approve", "ok", List.of());
        verify(repo).resolve(requestId, "approved", "ok");
    }

    @Test
    void listsPendingBySessionFromRepository() {
        HitlInteractionRepository repo = mock(HitlInteractionRepository.class);
        when(repo.findPendingBySession("s1")).thenReturn(List.of(
                HitlInteraction.builder().id("r1").sessionId("s1").type("question").prompt("Q1").createdAt(1L).build()
        ));

        PendingInteractionService service = new PendingInteractionService(repo, new ObjectMapper());
        List<PendingInteractionService.Interaction> pending = service.listPendingBySession("s1");
        assertEquals(1, pending.size());
        assertEquals("r1", pending.get(0).requestId());
    }

    @Test
    void returnsReplyFromResolvedRecord() {
        HitlInteractionRepository repo = mock(HitlInteractionRepository.class);
        when(repo.findById("r1")).thenReturn(Optional.of(
                HitlInteraction.builder().id("r1").status("reply").responseText("done").resolvedAt(100L).build()
        ));

        PendingInteractionService service = new PendingInteractionService(repo, new ObjectMapper());
        var reply = service.getReply("r1");
        assertEquals("reply", reply.action());
        assertEquals("done", reply.message());
    }
}
