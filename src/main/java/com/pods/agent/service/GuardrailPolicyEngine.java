package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.GuardrailPolicy;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.repository.GuardrailPolicyRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GuardrailPolicyEngine {
    private final GuardrailPolicyRepository policyRepository;
    private final RuntimeTuningProperties runtimeTuningProperties;

    public GuardrailPolicyEngine(GuardrailPolicyRepository policyRepository,
                                 RuntimeTuningProperties runtimeTuningProperties) {
        this.policyRepository = policyRepository;
        this.runtimeTuningProperties = runtimeTuningProperties;
    }

    public Decision evaluateTool(AgentTool tool) {
        if (tool == null) return new Decision("deny", "tool_not_found");
        if (tool.isExperimental()
                && !runtimeTuningProperties.isEnableExperimentalTools()
                && !runtimeTuningProperties.getEnabledExperimentalToolNames().stream().anyMatch(n -> n.equalsIgnoreCase(tool.getName()))) {
            return new Decision("deny", "experimental_tool_disabled");
        }
        if (tool.getPermissionScope() != null
                && !tool.getPermissionScope().isBlank()
                && runtimeTuningProperties.getAllowedPermissionScopes().stream().noneMatch(s -> s.equalsIgnoreCase(tool.getPermissionScope()))) {
            return new Decision("deny", "permission_scope_blocked:" + tool.getPermissionScope());
        }
        if (tool.isRequiresApproval()) {
            return new Decision("ask", "tool_requires_approval");
        }
        List<GuardrailPolicy> policies = policyRepository.findEnabled();
        Decision matched = null;
        for (GuardrailPolicy policy : policies) {
            if (!"tool".equalsIgnoreCase(policy.getScope())) continue;
            if ("name".equalsIgnoreCase(policy.getRuleType())
                    && tool.getName() != null
                    && tool.getName().equalsIgnoreCase(policy.getRuleValue())) {
                matched = new Decision(policy.getDecision(), "policy:" + policy.getName());
                break;
            }
        }
        if (matched != null) return matched;
        // open-dev default as requested
        return new Decision("allow", "open_dev_default");
    }

    public record Decision(String decision, String reason) {}
}
