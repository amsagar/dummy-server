package com.pods.agent.service;

import com.pods.agent.agent.AgentSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSummarizationServiceTest {

    @Test
    void compactionResultSignalsCompactionAndCounts() {
        ContextSummarizationService service = new ContextSummarizationService();
        AgentSession session = new AgentSession("s1");
        for (int i = 0; i < 10; i++) {
            session.getMessages().add(new UserMessage("User asks for summary context " + i + " ".repeat(50)));
            session.getMessages().add(new AssistantMessage("Assistant response " + i + " ".repeat(50)));
        }

        ContextSummarizationService.CompactionResult result =
                service.maybeSummarize(session, null, 20, "anthropic", 6);

        assertTrue(result.compacted());
        assertTrue(result.summary() != null && result.summary().contains("Rolling summary"));
        assertEquals(14, result.removedMessages());
        assertEquals(6, result.retainedMessages());
    }

    @Test
    void retainRecentMessagesTrimsSessionHistory() {
        ContextSummarizationService service = new ContextSummarizationService();
        AgentSession session = new AgentSession("s2");
        for (int i = 0; i < 8; i++) {
            session.getMessages().add(new UserMessage("u" + i));
        }

        var removed = service.retainRecentMessages(session, 3);

        assertEquals(5, removed.size());
        assertEquals(3, session.getMessages().size());
        assertEquals("u5", ((UserMessage) session.getMessages().get(0)).getText());
    }

    @Test
    void providerAwareEstimatorDiffersAcrossProviders() {
        ContextSummarizationService service = new ContextSummarizationService();
        AgentSession session = new AgentSession("s3");
        session.getMessages().add(new UserMessage("a".repeat(140)));

        long anthropic = service.estimateTokens(session, "anthropic");
        long openai = service.estimateTokens(session, "openai");

        assertFalse(anthropic == openai);
        assertTrue(anthropic > openai);
    }
}
