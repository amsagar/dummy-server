package com.pods.agent.workflow.api;

import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Option;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plugin catalog endpoints. Backs the n8n-style node library and the form
 * generator: the frontend reads {@link PluginDescriptor#properties()} to
 * render the per-node inspector and never needs to hardcode plugin schemas.
 */
@RestController
@RequestMapping("/api/v1/workflow/plugins")
public class PluginCatalogController {

    private final PluginCatalogService catalog;

    public PluginCatalogController(PluginCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<PluginDescriptor> descriptors = catalog.list();
        return ResponseEntity.ok(Map.of("plugins", descriptors));
    }

    @GetMapping("/{name}")
    public ResponseEntity<PluginDescriptor> get(@PathVariable("name") String name) {
        return catalog.get(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/options/{loaderId}")
    public ResponseEntity<Map<String, Object>> options(@PathVariable("loaderId") String loaderId) {
        List<Option> options = catalog.loadOptions(loaderId);
        return ResponseEntity.ok(Map.of("loaderId", loaderId, "options", options));
    }

    @PostMapping("/{name}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable("name") String name,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> properties = toStringKeyMap(body == null ? null : body.get("properties"));
        PluginCatalogService.PreviewResult r = catalog.preview(name, properties);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", r.success());
        payload.put("output", r.output());
        payload.put("error", r.error());
        payload.put("durationMs", r.durationMs());
        return ResponseEntity.ok(payload);
    }

    private static Map<String, Object> toStringKeyMap(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
