package com.pods.agent.workflow.plugin.descriptor;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Schema for a single plugin property — enough metadata to drive an n8n-style
 * form in the UI without the frontend needing to know about each plugin.
 *
 * <p>The frontend reads {@link Type} to pick the right field component and
 * {@link DisplayOption} to evaluate show/hide rules against the current form
 * state. {@code expressionAllowed} controls whether the field renders the
 * fixed-value/SecureSpel-expression toggle.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginPropertyDescriptor(
        String name,
        String label,
        String description,
        Type type,
        boolean required,
        Object defaultValue,
        boolean expressionAllowed,
        List<Option> options,
        String optionsLoader,
        List<PluginPropertyDescriptor> children,
        DisplayOption displayOptions,
        String placeholder
) {

    public enum Type {
        STRING,
        NUMBER,
        BOOLEAN,
        OPTIONS,
        MULTI_OPTIONS,
        COLLECTION,
        FIXED_COLLECTION,
        JSON,
        CODE,
        DATETIME,
        CREDENTIALS,
        NOTICE
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(String value, String label) {
        public static Option of(String value) { return new Option(value, value); }
        public static Option of(String value, String label) { return new Option(value, label); }
    }

    /**
     * show/hide rules. Each map entry is {@code field -> allowed values}; the
     * field is visible when ALL show entries match (any-of within an entry) and
     * NO hide entry matches.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisplayOption(Map<String, List<String>> show, Map<String, List<String>> hide) {
        public static DisplayOption show(String field, String... values) {
            return new DisplayOption(Map.of(field, List.of(values)), null);
        }
        public static DisplayOption hide(String field, String... values) {
            return new DisplayOption(null, Map.of(field, List.of(values)));
        }
    }

    /**
     * Builder-style helpers so plugin {@code describe()} bodies stay readable.
     * Records' canonical constructor takes 12 arguments; nobody wants to
     * pass {@code null} eight times to declare a string field.
     */
    public static final class Props {
        private Props() {}

        public static PluginPropertyDescriptor string(String name, String label, boolean required) {
            return new PluginPropertyDescriptor(name, label, null, Type.STRING, required, null, true, null, null, null, null, null);
        }

        public static PluginPropertyDescriptor number(String name, String label, boolean required, Number defaultValue) {
            return new PluginPropertyDescriptor(name, label, null, Type.NUMBER, required, defaultValue, true, null, null, null, null, null);
        }

        public static PluginPropertyDescriptor bool(String name, String label, boolean defaultValue) {
            return new PluginPropertyDescriptor(name, label, null, Type.BOOLEAN, false, defaultValue, true, null, null, null, null, null);
        }

        public static PluginPropertyDescriptor options(String name, String label, boolean required, String defaultValue, List<Option> options) {
            return new PluginPropertyDescriptor(name, label, null, Type.OPTIONS, required, defaultValue, false, options, null, null, null, null);
        }

        public static PluginPropertyDescriptor optionsDynamic(String name, String label, boolean required, String optionsLoader) {
            return new PluginPropertyDescriptor(name, label, null, Type.OPTIONS, required, null, false, null, optionsLoader, null, null, null);
        }

        public static PluginPropertyDescriptor json(String name, String label, boolean required) {
            return new PluginPropertyDescriptor(name, label, null, Type.JSON, required, null, true, null, null, null, null, null);
        }

        public static PluginPropertyDescriptor code(String name, String label, boolean required) {
            return new PluginPropertyDescriptor(name, label, null, Type.CODE, required, null, false, null, null, null, null, null);
        }

        public static PluginPropertyDescriptor collection(String name, String label, List<PluginPropertyDescriptor> children) {
            return new PluginPropertyDescriptor(name, label, null, Type.COLLECTION, false, null, false, null, null, children, null, null);
        }

        public static PluginPropertyDescriptor fixedCollection(String name, String label, List<PluginPropertyDescriptor> children) {
            return new PluginPropertyDescriptor(name, label, null, Type.FIXED_COLLECTION, false, null, false, null, null, children, null, null);
        }

        public static PluginPropertyDescriptor notice(String name, String body) {
            return new PluginPropertyDescriptor(name, null, body, Type.NOTICE, false, null, false, null, null, null, null, null);
        }
    }

    // ── Fluent helpers (records are immutable; these return modified copies) ──

    public PluginPropertyDescriptor withDescription(String description) {
        return new PluginPropertyDescriptor(name, label, description, type, required, defaultValue, expressionAllowed, options, optionsLoader, children, displayOptions, placeholder);
    }

    public PluginPropertyDescriptor withDefault(Object defaultValue) {
        return new PluginPropertyDescriptor(name, label, description, type, required, defaultValue, expressionAllowed, options, optionsLoader, children, displayOptions, placeholder);
    }

    public PluginPropertyDescriptor withExpression(boolean expressionAllowed) {
        return new PluginPropertyDescriptor(name, label, description, type, required, defaultValue, expressionAllowed, options, optionsLoader, children, displayOptions, placeholder);
    }

    public PluginPropertyDescriptor withPlaceholder(String placeholder) {
        return new PluginPropertyDescriptor(name, label, description, type, required, defaultValue, expressionAllowed, options, optionsLoader, children, displayOptions, placeholder);
    }

    public PluginPropertyDescriptor visibleWhen(String field, String... values) {
        return new PluginPropertyDescriptor(name, label, description, type, required, defaultValue, expressionAllowed, options, optionsLoader, children, DisplayOption.show(field, values), placeholder);
    }

    public PluginPropertyDescriptor hiddenWhen(String field, String... values) {
        return new PluginPropertyDescriptor(name, label, description, type, required, defaultValue, expressionAllowed, options, optionsLoader, children, DisplayOption.hide(field, values), placeholder);
    }
}
