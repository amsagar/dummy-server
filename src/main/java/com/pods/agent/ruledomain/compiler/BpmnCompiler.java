package com.pods.agent.ruledomain.compiler;

import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.invalidation.SkillSourceHasher;
import com.pods.agent.ruledomain.invalidation.ToolSignatureHasher;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot LLM call that translates a skill specification + user intent
 * into a deployable Flowable BPMN. Persists the result as a {@link RuleDomain}
 * row.
 *
 * Pipeline:
 *   <ol>
 *     <li>Build prompt (system + few-shots + skill md + tool catalog + user req)</li>
 *     <li>Call ChatClient → raw BPMN text</li>
 *     <li>Strip markdown fences if present</li>
 *     <li>Try to deploy via {@code repositoryService.createDeployment().addString(...)} —
 *         this is the actual XSD validation</li>
 *     <li>On parse failure, optionally retry once with the error appended to the prompt</li>
 *     <li>On success, embed the user message, persist as DRAFT</li>
 *   </ol>
 *
 * Note: this deploys to Flowable immediately as part of compilation, which
 * means the BPMN is ready to execute the moment the row is saved. The
 * caller (RuleDomainOrchestrator) typically follows with an execute().
 */
@Component
@Slf4j
public class BpmnCompiler {

    private static final Pattern PROC_ID =
            Pattern.compile("<process\\s+[^>]*id=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE_OPEN =
            Pattern.compile("^\\s*```(?:xml)?\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE_CLOSE = Pattern.compile("```\\s*$");

    private final ModelProviderRouter modelProviderRouter;
    private final EmbeddingProviderRouter embeddingRouter;
    private final RuleDomainProperties props;
    private final CompilerPromptBuilder promptBuilder;
    private final RepositoryService repositoryService;
    private final RuleDomainRepository repo;
    private final SkillSourceHasher skillHasher;
    private final ToolSignatureHasher toolHasher;

    public BpmnCompiler(ModelProviderRouter modelProviderRouter,
                        EmbeddingProviderRouter embeddingRouter,
                        RuleDomainProperties props,
                        CompilerPromptBuilder promptBuilder,
                        RepositoryService repositoryService,
                        RuleDomainRepository repo,
                        SkillSourceHasher skillHasher,
                        ToolSignatureHasher toolHasher) {
        this.modelProviderRouter = modelProviderRouter;
        this.embeddingRouter = embeddingRouter;
        this.props = props;
        this.promptBuilder = promptBuilder;
        this.repositoryService = repositoryService;
        this.repo = repo;
        this.skillHasher = skillHasher;
        this.toolHasher = toolHasher;
    }

    public RuleDomain compile(String skillId,
                              String skillName,
                              String skillMarkdown,
                              String userRequest,
                              String intentLabel,
                              List<AgentTool> tools) {
        ModelRef compilerRef = new ModelRef(
                props.getCompilerModel().getProviderId(),
                props.getCompilerModel().getModelId());
        ModelProviderRouter.Spec spec = modelProviderRouter.resolve(compilerRef, true);
        ChatClient client = spec.client();

        String system = promptBuilder.buildSystem();
        String user = promptBuilder.buildUser(skillMarkdown, tools, userRequest);

        // First attempt
        String xml = invokeLlm(client, spec.options(), system, user);
        xml = sanitize(xml);
        String error = tryDeploy(xml, skillName, intentLabel);
        int attempts = 1;
        if (error != null && props.getMaxCompileAttempts() > 1) {
            log.warn("First compile attempt rejected: {}", error);
            String repairUser = promptBuilder.buildRepair(xml, error);
            xml = sanitize(invokeLlm(client, spec.options(), system, repairUser));
            error = tryDeploy(xml, skillName, intentLabel);
            attempts = 2;
        }

        long now = System.currentTimeMillis();
        RuleDomain.RuleDomainBuilder builder = RuleDomain.builder()
                .skillId(skillId)
                .skillName(skillName)
                .intentLabel(intentLabel)
                .sourceHash(skillHasher.hash(skillMarkdown))
                .toolSignature(toolHasher.hash(tools))
                .bpmnXml(xml)
                .compileAttempts(attempts)
                .version(repo.latestVersion(skillId, intentLabel) + 1)
                .createdAt(now)
                .updatedAt(now);

        if (error != null) {
            log.error("BPMN compilation failed after {} attempts: {}", attempts, error);
            RuleDomain failed = builder
                    .status(RuleDomain.STATUS_FAILED)
                    .flowableProcKey("")
                    .lastError(error)
                    .build();
            return repo.save(failed, null);
        }

        String procKey = extractProcessId(xml);
        if (procKey == null) {
            String msg = "Compiled BPMN has no <process id=...>";
            RuleDomain failed = builder
                    .status(RuleDomain.STATUS_FAILED)
                    .flowableProcKey("")
                    .lastError(msg)
                    .build();
            return repo.save(failed, null);
        }

        float[] embedding = embed(userRequest);
        RuleDomain saved = builder
                .status(RuleDomain.STATUS_DRAFT)
                .flowableProcKey(procKey)
                .build();
        return repo.save(saved, embedding);
    }

    private String invokeLlm(ChatClient client,
                             org.springframework.ai.chat.prompt.ChatOptions options,
                             String system,
                             String user) {
        var req = client.prompt().system(system).user(user);
        if (options != null) req = req.options(options);
        return req.call().content();
    }

    /** Attempt a real Flowable deployment; rollback by deleting on success here, since this
     *  is only a validation step. Real deployment happens later via BpmnRuntime.ensureDeployed.
     *  Returns null on success or the parse error message on failure. */
    private String tryDeploy(String xml, String skillName, String intentLabel) {
        if (xml == null || xml.isBlank()) return "compiler returned empty output";
        String resourceName = "validate-" + slug(skillName) + "-" + slug(intentLabel) + ".bpmn20.xml";
        try {
            Deployment d = repositoryService.createDeployment()
                    .name("validate:" + skillName + ":" + intentLabel)
                    .addString(resourceName, xml)
                    .deploy();
            // Validation pass: tear down the deployment immediately so we don't pollute
            // the engine with throwaway versions. The real deployment happens via
            // BpmnRuntime.ensureDeployed when the domain is actually used.
            repositoryService.deleteDeployment(d.getId(), true);
            return null;
        } catch (Exception ex) {
            return rootCauseMessage(ex);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    private static String sanitize(String llmOutput) {
        if (llmOutput == null) return "";
        String s = llmOutput.trim();
        Matcher open = CODE_FENCE_OPEN.matcher(s);
        if (open.find() && open.start() == 0) s = s.substring(open.end());
        Matcher close = CODE_FENCE_CLOSE.matcher(s);
        if (close.find()) s = s.substring(0, close.start());
        return s.trim();
    }

    private static String extractProcessId(String xml) {
        Matcher m = PROC_ID.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String slug(String s) {
        if (s == null) return "x";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private float[] embed(String text) {
        try {
            String pid = props.getEmbeddingModel().getProviderId();
            String mid = props.getEmbeddingModel().getModelId();
            ModelRef ref;
            if (pid == null || pid.isBlank()) {
                ref = embeddingRouter.findDefault()
                        .map(mc -> new ModelRef(mc.getProviderId(), mc.getModelId()))
                        .orElse(null);
            } else {
                ref = new ModelRef(pid, mid);
            }
            if (ref == null) {
                log.warn("No default embedding model configured; intent matching disabled");
                return null;
            }
            EmbeddingModel model = embeddingRouter.resolve(ref);
            return model.embed(text);
        } catch (Exception ex) {
            log.warn("Failed to embed intent text: {}", ex.getMessage());
            return null;
        }
    }
}
