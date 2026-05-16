package com.pods.agent.ruledomain.compiler;

import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.invalidation.SkillSourceHasher;
import com.pods.agent.ruledomain.invalidation.ToolSignatureHasher;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ErrorEventDefinition;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.api.io.InputStreamProvider;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final RuleDomainEventBus bus;

    public BpmnCompiler(ModelProviderRouter modelProviderRouter,
                        EmbeddingProviderRouter embeddingRouter,
                        RuleDomainProperties props,
                        CompilerPromptBuilder promptBuilder,
                        RepositoryService repositoryService,
                        RuleDomainRepository repo,
                        SkillSourceHasher skillHasher,
                        ToolSignatureHasher toolHasher,
                        RuleDomainEventBus bus) {
        this.modelProviderRouter = modelProviderRouter;
        this.embeddingRouter = embeddingRouter;
        this.props = props;
        this.promptBuilder = promptBuilder;
        this.repositoryService = repositoryService;
        this.repo = repo;
        this.skillHasher = skillHasher;
        this.toolHasher = toolHasher;
        this.bus = bus;
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

        bus.emit("rule_domain.compile.llm_call", Map.of(
                "model", compilerRef.modelID(), "provider", compilerRef.providerID()));

        // First attempt
        long llmStart = System.currentTimeMillis();
        String xml = invokeLlm(client, spec.options(), system, user);
        long llmMs = System.currentTimeMillis() - llmStart;
        xml = sanitize(xml);
        xml = injectErrorBoundaries(xml);
        bus.emit("rule_domain.compile.validating", Map.of("llmMs", llmMs));
        String error = tryDeploy(xml, skillName, intentLabel);
        int attempts = 1;
        if (error != null && props.getMaxCompileAttempts() > 1) {
            log.warn("First compile attempt rejected: {}", error);
            bus.emit("rule_domain.compile.repair_attempt", Map.of("error", error));
            String repairUser = promptBuilder.buildRepair(xml, error);
            xml = sanitize(invokeLlm(client, spec.options(), system, repairUser));
            xml = injectErrorBoundaries(xml);
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

        bus.emit("rule_domain.compile.deployed", Map.of("procKey", procKey, "attempts", attempts));
        bus.emit("rule_domain.compile.embedding", Map.of());
        float[] embedding = embed(userRequest);
        RuleDomain saved = builder
                .status(RuleDomain.STATUS_DRAFT)
                .flowableProcKey(procKey)
                .build();
        RuleDomain persisted = repo.save(saved, embedding);
        bus.emit("rule_domain.compile.saved", Map.of(
                "domainId", persisted.getId() == null ? "" : persisted.getId(),
                "procKey", procKey));
        return persisted;
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
    public String tryDeploy(String xml, String skillName, String intentLabel) {
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

    /** Matches the (illegal-on-delegate-tasks) {@code resultVariableName="..."} attribute. */
    private static final Pattern RESULT_VAR_ATTR =
            Pattern.compile("\\s+(?:flowable:)?resultVariableName\\s*=\\s*\"[^\"]*\"");

    public static String sanitize(String llmOutput) {
        if (llmOutput == null) return "";
        String s = llmOutput.trim();
        Matcher open = CODE_FENCE_OPEN.matcher(s);
        if (open.find() && open.start() == 0) s = s.substring(open.end());
        Matcher close = CODE_FENCE_CLOSE.matcher(s);
        if (close.find()) s = s.substring(0, close.start());
        // Strip resultVariableName="..." attributes — Flowable rejects them on
        // service tasks using delegateExpression/class. The delegates already
        // write to process variables via the outputBinding field; this
        // attribute is always redundant on our generated BPMNs.
        s = RESULT_VAR_ATTR.matcher(s).replaceAll("");
        return s.trim();
    }

    /**
     * Attach a {@code <bpmn:boundaryEvent>} with {@code errorCode="TOOL_EXECUTION_FAILED"}
     * to every service task wired through {@code ${toolCallDelegate}}. All boundaries
     * route to a single shared error end event so a failed tool call terminates the
     * process cleanly (with {@code error} set on the historic process instance)
     * instead of escaping as an uncaught {@link org.flowable.engine.delegate.BpmnError}.
     *
     * <p>The {@code _failedTool} process variable is set by {@code ToolCallDelegate}
     * before it throws, so the orchestrator can include it in the user-facing
     * advisory message on fallback.
     *
     * <p>On any parse failure this method returns the input XML unchanged — the
     * primary deploy pass in {@link #tryDeploy} is still the source of truth for
     * compile validity.
     */
    public static String injectErrorBoundaries(String xml) {
        if (xml == null || xml.isBlank()) return xml;
        try {
            BpmnXMLConverter converter = new BpmnXMLConverter();
            InputStreamProvider isp = () -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            BpmnModel model = converter.convertToBpmnModel(isp, false, false);
            if (model == null || model.getProcesses() == null || model.getProcesses().isEmpty()) return xml;

            boolean changed = false;
            for (Process proc : model.getProcesses()) {
                changed |= attachBoundariesToProc(proc);
            }
            if (!changed) return xml;

            byte[] out = converter.convertToXML(model);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            // Don't fail compilation just because boundary injection couldn't run.
            // The tryDeploy() step below catches genuine XML problems.
            return xml;
        }
    }

    private static boolean attachBoundariesToProc(Process proc) {
        List<ServiceTask> toolTasks = new ArrayList<>();
        for (FlowElement fe : proc.getFlowElements()) {
            if (fe instanceof ServiceTask st && isToolCallDelegate(st)) {
                if (st.getBoundaryEvents() != null && !st.getBoundaryEvents().isEmpty()) {
                    boolean alreadyHandled = st.getBoundaryEvents().stream().anyMatch(b ->
                            b.getEventDefinitions() != null && b.getEventDefinitions().stream()
                                    .anyMatch(d -> d instanceof ErrorEventDefinition));
                    if (alreadyHandled) continue;
                }
                toolTasks.add(st);
            }
        }
        if (toolTasks.isEmpty()) return false;

        EndEvent sharedEnd = ensureSharedErrorEnd(proc);

        for (ServiceTask st : toolTasks) {
            String beId = st.getId() + "_onError";
            BoundaryEvent be = new BoundaryEvent();
            be.setId(beId);
            be.setAttachedToRefId(st.getId());
            be.setAttachedToRef(st);
            be.setCancelActivity(true);

            ErrorEventDefinition eed = new ErrorEventDefinition();
            eed.setErrorCode("TOOL_EXECUTION_FAILED");
            be.addEventDefinition(eed);

            proc.addFlowElement(be);
            if (st.getBoundaryEvents() == null) st.setBoundaryEvents(new ArrayList<>());
            st.getBoundaryEvents().add(be);

            SequenceFlow sf = new SequenceFlow(beId, sharedEnd.getId());
            sf.setId(beId + "_flow");
            proc.addFlowElement(sf);
        }
        return true;
    }

    private static boolean isToolCallDelegate(ServiceTask st) {
        String impl = st.getImplementation();
        return impl != null && impl.contains("toolCallDelegate");
    }

    private static EndEvent ensureSharedErrorEnd(Process proc) {
        String sharedId = "endOnToolFailure";
        FlowElement existing = proc.getFlowElement(sharedId);
        if (existing instanceof EndEvent ee) return ee;

        EndEvent ee = new EndEvent();
        ee.setId(sharedId);
        ee.setName("Tool failure");
        ErrorEventDefinition errDef = new ErrorEventDefinition();
        errDef.setErrorCode("TOOL_EXECUTION_FAILED");
        ee.addEventDefinition(errDef);
        proc.addFlowElement(ee);
        return ee;
    }


    public static String extractProcessId(String xml) {
        Matcher m = PROC_ID.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String slug(String s) {
        if (s == null) return "x";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public float[] embed(String text) {
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
