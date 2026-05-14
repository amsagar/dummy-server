package com.pods.agent.workflow.proposal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.WorkflowProposalProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.SkillRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

class WorkflowAlignmentJudgeTest {

    @Test
    void judgeFailsClosedWhenNoModelAvailable() {
        WorkflowAlignmentJudge judge = new WorkflowAlignmentJudge(
                mock(ModelProviderRouter.class),
                mock(SkillRegistryService.class),
                new WorkflowProposalProperties(),
                new ObjectMapper());

        WorkflowAlignmentJudge.Verdict verdict = judge.judge(
                "{\"name\":\"x\"}",
                java.util.List.of(),
                "{}",
                "reason",
                null);

        assertFalse(verdict.aligned());
        assertTrue(verdict.critique().contains("alignment_no_model"));
    }

    @Test
    void judgeFailsClosedOnMalformedJudgeResponse() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        ModelProviderRouter.Spec spec = mock(ModelProviderRouter.Spec.class);
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(router.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(spec);
        when(spec.client()).thenReturn(chatClient);
        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .content()).thenReturn("not-json");

        WorkflowAlignmentJudge judge = new WorkflowAlignmentJudge(
                router,
                mock(SkillRegistryService.class),
                new WorkflowProposalProperties(),
                new ObjectMapper());

        WorkflowAlignmentJudge.Verdict verdict = judge.judge(
                "{\"name\":\"x\"}",
                java.util.List.of(),
                "{}",
                "reason",
                new ModelRef("openai", "gpt-4o"));

        assertFalse(verdict.aligned());
        assertTrue(verdict.critique().contains("alignment_unparseable"));
    }
}
