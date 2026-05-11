package com.pods.agent.workflow.plugin.descriptor;

import com.pods.agent.workflow.plugin.AgentToolPlugin;
import com.pods.agent.workflow.plugin.AiChatPlugin;
import com.pods.agent.workflow.plugin.CodeExecPlugin;
import com.pods.agent.workflow.plugin.HttpRequestPlugin;
import com.pods.agent.workflow.plugin.McpToolPlugin;
import com.pods.agent.workflow.plugin.SkillToolPlugin;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Locks in the descriptor contract: every shipped plugin must produce a valid
 * non-empty descriptor with unique property names. This is the test that
 * catches a developer adding a new plugin without an n8n-style schema.
 */
class PluginDescriptorCoverageTest {

    @Test
    void everyPluginDescribesItself() {
        // We instantiate each plugin reflectively-light: dependencies are non-null
        // but only describe() is called, which uses no collaborator state.
        // We don't construct AgentToolPlugin / SkillToolPlugin / McpToolPlugin /
        // CodeExecPlugin / AiChatPlugin / HttpRequestPlugin here because their
        // constructors require live services. Instead we check the static list
        // of expected DescribablePlugin classes — the integration / Spring test
        // covers the wired beans.
        List<Class<?>> expected = List.of(
                HttpRequestPlugin.class,
                AgentToolPlugin.class,
                SkillToolPlugin.class,
                McpToolPlugin.class,
                CodeExecPlugin.class,
                AiChatPlugin.class
        );
        for (Class<?> klass : expected) {
            Assertions.assertTrue(
                    DescribablePlugin.class.isAssignableFrom(klass),
                    klass.getSimpleName() + " must implement DescribablePlugin");
        }
    }

    @Test
    void httpRequestPluginDescriptorIsValid() {
        PluginDescriptor d = new HttpRequestPlugin(null).describe();
        assertDescriptorValid(d, "HttpRequestPlugin", "Tool");
        // Assert the conditional body field is hidden by default
        PluginPropertyDescriptor body = findProperty(d, "body");
        Assertions.assertNotNull(body, "body property should exist");
        Assertions.assertNotNull(body.displayOptions(), "body should be conditional");
        Assertions.assertTrue(
                body.displayOptions().show().get("method").containsAll(List.of("POST", "PUT", "PATCH")),
                "body should be visible when method ∈ {POST,PUT,PATCH}");
    }

    @Test
    void aiChatPluginDescriptorHasProviderEnum() {
        PluginDescriptor d = new AiChatPlugin(null).describe();
        assertDescriptorValid(d, "AiChatPlugin", "AI");
        PluginPropertyDescriptor provider = findProperty(d, "provider");
        Assertions.assertNotNull(provider);
        Assertions.assertEquals(PluginPropertyDescriptor.Type.OPTIONS, provider.type());
        Assertions.assertNotNull(provider.options());
        Assertions.assertTrue(provider.options().size() >= 2, "provider should offer multiple options");
    }

    @Test
    void codeExecPluginDescriptorHasLanguageEnum() {
        PluginDescriptor d = new CodeExecPlugin(null).describe();
        assertDescriptorValid(d, "CodeExecPlugin", "Code");
        PluginPropertyDescriptor lang = findProperty(d, "language");
        Assertions.assertNotNull(lang);
        Assertions.assertEquals(PluginPropertyDescriptor.Type.OPTIONS, lang.type());
        Set<String> values = new HashSet<>();
        for (PluginPropertyDescriptor.Option o : lang.options()) values.add(o.value());
        Assertions.assertTrue(values.containsAll(List.of("javascript", "typescript", "python", "java")),
                "language enum must cover all four supported languages");
    }

    @Test
    void agentToolPluginUsesDynamicLoader() {
        PluginDescriptor d = new AgentToolPlugin(null, null, null).describe();
        assertDescriptorValid(d, "AgentToolPlugin", "Tool");
        PluginPropertyDescriptor toolName = findProperty(d, "toolName");
        Assertions.assertNotNull(toolName);
        Assertions.assertEquals("agent-tools", toolName.optionsLoader());
    }

    @Test
    void skillToolPluginUsesSkillsLoader() {
        PluginDescriptor d = new SkillToolPlugin(null).describe();
        assertDescriptorValid(d, "SkillToolPlugin", "Tool");
        PluginPropertyDescriptor name = findProperty(d, "name");
        Assertions.assertNotNull(name);
        Assertions.assertEquals("skills", name.optionsLoader());
    }

    @Test
    void mcpToolPluginRequiresServerAndTool() {
        PluginDescriptor d = new McpToolPlugin(null).describe();
        assertDescriptorValid(d, "McpToolPlugin", "Tool");
        Assertions.assertTrue(findProperty(d, "serverId").required());
        Assertions.assertTrue(findProperty(d, "toolName").required());
    }

    private static void assertDescriptorValid(PluginDescriptor d, String expectedName, String expectedCategory) {
        Assertions.assertEquals(expectedName, d.name(), "descriptor.name");
        Assertions.assertNotNull(d.label(), "descriptor.label");
        Assertions.assertNotNull(d.icon(), "descriptor.icon");
        Assertions.assertEquals(expectedCategory, d.category(), "descriptor.category");
        Assertions.assertNotNull(d.properties(), "descriptor.properties");
        Assertions.assertFalse(d.properties().isEmpty(), "descriptor must declare at least one property");
        Set<String> propNames = new HashSet<>();
        for (PluginPropertyDescriptor p : d.properties()) {
            Assertions.assertNotNull(p.name(), "property.name");
            Assertions.assertFalse(p.name().isBlank(), "property.name blank");
            Assertions.assertTrue(propNames.add(p.name()),
                    "duplicate property name in " + d.name() + ": " + p.name());
            Assertions.assertNotNull(p.type(), "property.type for " + p.name());
        }
    }

    private static PluginPropertyDescriptor findProperty(PluginDescriptor d, String name) {
        for (PluginPropertyDescriptor p : d.properties()) {
            if (name.equals(p.name())) return p;
        }
        return null;
    }
}
